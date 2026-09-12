package com.lrj.wms.inventory.inventory;

import com.lrj.wms.inventory.effect.domain.EffectCodes;
import com.lrj.wms.inventory.effect.infrastructure.EffectMapper;
import com.lrj.wms.inventory.inventory.domain.CommandDigest;
import com.lrj.wms.inventory.inventory.domain.Quantity;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import com.lrj.wms.inventory.inventory.domain.StockCommandCodes;
import com.lrj.wms.inventory.inventory.infrastructure.StockCommandMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;

/**
 * T2：受理来源命令、写余额/流水/凭证，或建立取消墓碑。
 * 入口先锁 stock_effect。同事实换键复用原命令；安全关闭后才发下一尝试。
 */
public final class StockCommandService {
    private final SqlSession session;
    private final Clock clock;

    public StockCommandService(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    /** 收货过账。同命令重放返回原结果；墓碑返回 CANCELLED。 */
    public Map<String, Object> applyReceive(String enterpriseId, String warehouseId, String sourceService, String commandId,
            String factParentId, String factPartId, String factLineId, String documentId, String actorId,
            String sourceExecutionId, StockBucketKey bucket, Quantity qty) {
        return applyReceive(enterpriseId, warehouseId, sourceService, commandId, factParentId, factPartId, factLineId,
                documentId, actorId, sourceExecutionId, bucket, qty, null);
    }

    /** 收货过账；previousCommandId 非空表示安全关闭后的下一尝试。 */
    public Map<String, Object> applyReceive(String enterpriseId, String warehouseId, String sourceService, String commandId,
            String factParentId, String factPartId, String factLineId, String documentId, String actorId,
            String sourceExecutionId, StockBucketKey bucket, Quantity qty, String previousCommandId) {
        EffectCodes.requireAction(EffectCodes.ACTION_RECEIVE);
        Timestamp now = Timestamp.from(clock.instant());
        EffectMapper effects = session.getMapper(EffectMapper.class);
        StockCommandMapper commands = session.getMapper(StockCommandMapper.class);
        String digest = CommandDigest.v1(EffectCodes.ACTION_RECEIVE, documentId, bucket, qty.toPlainString(), commandId);
        String effectId = ensureEffect(effects, enterpriseId, warehouseId, sourceService, EffectCodes.ACTION_RECEIVE,
                EffectCodes.FACT_RECEIPT_PART, factParentId, factPartId, factLineId, now);
        Map<String, Object> effect = effects.lockEffect(enterpriseId, warehouseId, effectId);
        Map<String, Object> reused = reuseExisting(commands, enterpriseId, warehouseId, sourceService, commandId,
                EffectCodes.ACTION_RECEIVE, effectId, effect, previousCommandId, digest);
        if (reused != null) {
            return view(reused);
        }
        long attemptNo = acceptCommand(effects, commands, enterpriseId, warehouseId, sourceService, commandId,
                EffectCodes.ACTION_RECEIVE, effectId, digest, previousCommandId, effect, now);
        if (replayTerminal(commands, enterpriseId, warehouseId, sourceService, commandId, digest) != null) {
            return get(enterpriseId, warehouseId, sourceService, commandId);
        }
        if (effects.casBindActive(enterpriseId, warehouseId, effectId, commandId, attemptNo, EffectCodes.STATE_OPEN,
                now) != 1 && previousCommandId == null) {
            throw new InventoryException("VERSION_CONFLICT", "效果活动命令冲突");
        }
        String attemptId = UUID.randomUUID().toString();
        effects.insertAttempt(attemptId, enterpriseId, warehouseId, effectId, commandId, blankToNull(previousCommandId),
                attemptNo, EffectCodes.STATE_OPEN, CommandDigest.VERSION_1, digest, digest, now);
        new InventoryApplicationService(session, clock).receive(enterpriseId, warehouseId, commandId, documentId, actorId,
                bucket, qty);
        String postingId = UUID.randomUUID().toString();
        commands.insertPosting(postingId, enterpriseId, warehouseId, sourceService, commandId, effectId,
                EffectCodes.ACTION_RECEIVE, attemptId, StockCommandCodes.POSTING_RECEIPT, qty.toBigDecimal(),
                sourceExecutionId == null || sourceExecutionId.isBlank() ? StockCommandCodes.NO_SOURCE_EXECUTION
                        : sourceExecutionId,
                documentId, "{\"operationId\":\"" + commandId + "\"}", now);
        commands.insertPermit(UUID.randomUUID().toString(), enterpriseId, warehouseId, sourceService, commandId, effectId,
                attemptId, attemptNo, documentId, 1L, EffectCodes.ACTION_RECEIVE, digest, qty.toBigDecimal(),
                qty.toBigDecimal(), BigDecimal.ZERO, StockCommandCodes.PERMIT_POSTED, now, now, now);
        if (effects.casApply(enterpriseId, warehouseId, effectId, commandId, now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "效果过账冲突");
        }
        if (commands.casState(enterpriseId, warehouseId, sourceService, commandId, StockCommandCodes.CMD_PENDING,
                StockCommandCodes.CMD_APPLIED, "{\"postingId\":\"" + postingId + "\"}", now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "命令过账冲突");
        }
        return get(enterpriseId, warehouseId, sourceService, commandId);
    }

    /** 未执行命令取消墓碑。已过账返回原 APPLIED。已 STARTED 拒绝。 */
    public Map<String, Object> cancel(String enterpriseId, String warehouseId, String sourceService, String commandId,
            String action, String effectId, String digest) {
        Timestamp now = Timestamp.from(clock.instant());
        EffectMapper effects = session.getMapper(EffectMapper.class);
        StockCommandMapper commands = session.getMapper(StockCommandMapper.class);
        Map<String, Object> effect = effects.lockEffect(enterpriseId, warehouseId, effectId);
        if (effect == null) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "效果不存在");
        }
        if (effect.get("applied_command_id") != null) {
            if (commandId.equals(String.valueOf(effect.get("applied_command_id")))) {
                return get(enterpriseId, warehouseId, sourceService, commandId);
            }
            throw new InventoryException("EFFECT_ALREADY_APPLIED", "已过账不得取消为墓碑");
        }
        Map<String, Object> permit = commands.lockPermitByCommand(enterpriseId, warehouseId, sourceService, commandId);
        refuseIfOccupying(permit);
        commands.insertIgnore(enterpriseId, warehouseId, sourceService, commandId, action, effectId, commandId, 1L, null,
                digest, CommandDigest.VERSION_1, StockCommandCodes.CMD_CANCELLED, now);
        Map<String, Object> row = commands.lockByCommand(enterpriseId, warehouseId, sourceService, commandId);
        if (row == null) {
            throw new InventoryException("VERSION_CONFLICT", "命令墓碑竞争");
        }
        if (StockCommandCodes.CMD_APPLIED.equals(String.valueOf(row.get("state")))) {
            return view(row);
        }
        if (StockCommandCodes.CMD_PENDING.equals(String.valueOf(row.get("state")))) {
            if (commands.casState(enterpriseId, warehouseId, sourceService, commandId, StockCommandCodes.CMD_PENDING,
                    StockCommandCodes.CMD_CANCELLED, "{\"reason\":\"CANCELLED\"}", now) != 1) {
                throw new InventoryException("VERSION_CONFLICT", "命令取消冲突");
            }
        } else if (!StockCommandCodes.CMD_CANCELLED.equals(String.valueOf(row.get("state")))) {
            throw new InventoryException("INVALID_STATE", "命令状态不可取消");
        }
        effects.casCancelActive(enterpriseId, warehouseId, effectId, commandId, EffectCodes.STATE_CANCELLED, now);
        return get(enterpriseId, warehouseId, sourceService, commandId);
    }

    /** 业务拒绝未执行命令。REJECTED 永久保持，安全关闭证据另存。 */
    public Map<String, Object> reject(String enterpriseId, String warehouseId, String sourceService, String commandId,
            String effectId) {
        Timestamp now = Timestamp.from(clock.instant());
        EffectMapper effects = session.getMapper(EffectMapper.class);
        StockCommandMapper commands = session.getMapper(StockCommandMapper.class);
        Map<String, Object> effect = effects.lockEffect(enterpriseId, warehouseId, effectId);
        if (effect == null) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "效果不存在");
        }
        if (effect.get("applied_command_id") != null) {
            throw new InventoryException("EFFECT_ALREADY_APPLIED", "已过账不得拒绝为REJECTED");
        }
        String state = String.valueOf(effect.get("state"));
        if (EffectCodes.STATE_STARTED.equals(state) || EffectCodes.STATE_UNKNOWN.equals(state)) {
            throw new InventoryException("STALE_EXECUTION_ATTEMPT", "STARTED/UNKNOWN 不得业务拒绝后重做");
        }
        refuseIfOccupying(commands.lockPermitByCommand(enterpriseId, warehouseId, sourceService, commandId));
        Map<String, Object> row = commands.lockByCommand(enterpriseId, warehouseId, sourceService, commandId);
        if (row == null) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "命令不存在");
        }
        if (StockCommandCodes.CMD_REJECTED.equals(String.valueOf(row.get("state")))) {
            return view(row);
        }
        if (commands.casState(enterpriseId, warehouseId, sourceService, commandId, StockCommandCodes.CMD_PENDING,
                StockCommandCodes.CMD_REJECTED, "{\"reason\":\"REJECTED\"}", now) != 1) {
            throw new InventoryException("INVALID_STATE", "命令状态不可拒绝");
        }
        return get(enterpriseId, warehouseId, sourceService, commandId);
    }

    /** 未过账命令写入安全关闭证据，效果进入 SAFE_CLOSED 后才允许下一尝试。 */
    public Map<String, Object> safeClose(String enterpriseId, String warehouseId, String sourceService, String commandId,
            String effectId) {
        Timestamp now = Timestamp.from(clock.instant());
        EffectMapper effects = session.getMapper(EffectMapper.class);
        StockCommandMapper commands = session.getMapper(StockCommandMapper.class);
        Map<String, Object> effect = effects.lockEffect(enterpriseId, warehouseId, effectId);
        if (effect == null) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "效果不存在");
        }
        if (effect.get("applied_command_id") != null) {
            throw new InventoryException("EFFECT_ALREADY_APPLIED", "已过账不得安全关闭后重做");
        }
        String state = String.valueOf(effect.get("state"));
        if (EffectCodes.STATE_STARTED.equals(state) || EffectCodes.STATE_UNKNOWN.equals(state)) {
            throw new InventoryException("STALE_EXECUTION_ATTEMPT", "STARTED/UNKNOWN 不得发新尝试");
        }
        Map<String, Object> permit = commands.lockPermitByCommand(enterpriseId, warehouseId, sourceService, commandId);
        refuseIfOccupying(permit);
        String closeId = UUID.randomUUID().toString();
        if (commands.markSafeClose(enterpriseId, warehouseId, sourceService, commandId, closeId,
                "{\"commandId\":\"" + commandId + "\"}", now) != 1) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "命令不存在");
        }
        if (effects.casSafeClose(enterpriseId, warehouseId, effectId, commandId, now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "安全关闭冲突");
        }
        Map<String, Object> body = get(enterpriseId, warehouseId, sourceService, commandId);
        body.put("safeCloseRef", closeId);
        return body;
    }

    /** 补偿过账：新效果引用原 posting，同 casePart 只入账一次。不改原收货余额。 */
    public Map<String, Object> applyCompensate(String enterpriseId, String warehouseId, String sourceService,
            String commandId, String caseParentId, String casePartId, String caseLineId, String originalPostingId,
            String documentId, Quantity qty) {
        EffectCodes.requireAction(EffectCodes.ACTION_COMPENSATE);
        Timestamp now = Timestamp.from(clock.instant());
        EffectMapper effects = session.getMapper(EffectMapper.class);
        StockCommandMapper commands = session.getMapper(StockCommandMapper.class);
        String digest = CommandDigest.v1Parts(EffectCodes.ACTION_COMPENSATE, documentId, originalPostingId,
                qty.toPlainString(), commandId);
        String effectId = ensureEffect(effects, enterpriseId, warehouseId, sourceService, EffectCodes.ACTION_COMPENSATE,
                EffectCodes.FACT_CASE_PART, caseParentId, casePartId, caseLineId, now);
        Map<String, Object> effect = effects.lockEffect(enterpriseId, warehouseId, effectId);
        Map<String, Object> reused = reuseExisting(commands, enterpriseId, warehouseId, sourceService, commandId,
                EffectCodes.ACTION_COMPENSATE, effectId, effect, null, digest);
        if (reused != null && !StockCommandCodes.CMD_DEFERRED.equals(String.valueOf(reused.get("state")))) {
            return view(reused);
        }
        Map<String, Object> original = commands.lockPosting(enterpriseId, warehouseId, originalPostingId);
        if (original == null) {
            commands.insertIgnore(enterpriseId, warehouseId, sourceService, commandId, EffectCodes.ACTION_COMPENSATE,
                    effectId, commandId, 1L, null, digest, CommandDigest.VERSION_1, StockCommandCodes.CMD_DEFERRED, now);
            Map<String, Object> deferred = commands.lockByCommand(enterpriseId, warehouseId, sourceService, commandId);
            if (deferred == null) {
                throw new InventoryException("VERSION_CONFLICT", "补偿延期竞争");
            }
            if (!digest.equals(String.valueOf(deferred.get("payload_digest")))) {
                throw new InventoryException("COMMAND_CONFLICT", "同命令不同内容");
            }
            return view(deferred);
        }
        if (commands.addReversed(enterpriseId, warehouseId, originalPostingId, qty.toBigDecimal(), now) != 1) {
            throw new InventoryException("OVER_REVERSE", "逆向累计超过原凭证可逆量");
        }
        commands.insertIgnore(enterpriseId, warehouseId, sourceService, commandId, EffectCodes.ACTION_COMPENSATE, effectId,
                commandId, 1L, null, digest, CommandDigest.VERSION_1, StockCommandCodes.CMD_PENDING, now);
        commands.casState(enterpriseId, warehouseId, sourceService, commandId, StockCommandCodes.CMD_DEFERRED,
                StockCommandCodes.CMD_PENDING, "{\"reason\":\"ORIGINAL_ARRIVED\"}", now);
        Map<String, Object> terminal = replayTerminal(commands, enterpriseId, warehouseId, sourceService, commandId, digest);
        if (terminal != null) {
            return view(terminal);
        }
        if (effects.casBindActive(enterpriseId, warehouseId, effectId, commandId, 1L, EffectCodes.STATE_OPEN, now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "补偿效果冲突");
        }
        String attemptId = UUID.randomUUID().toString();
        commands.insertCompensationPosting(UUID.randomUUID().toString(), enterpriseId, warehouseId, sourceService, commandId,
                effectId, EffectCodes.ACTION_COMPENSATE, attemptId, StockCommandCodes.POSTING_COMPENSATION,
                qty.toBigDecimal(), StockCommandCodes.NO_SOURCE_EXECUTION, documentId,
                "{\"originalPostingId\":\"" + originalPostingId + "\"}", originalPostingId, now);
        if (effects.casApply(enterpriseId, warehouseId, effectId, commandId, now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "补偿过账冲突");
        }
        if (commands.casState(enterpriseId, warehouseId, sourceService, commandId, StockCommandCodes.CMD_PENDING,
                StockCommandCodes.CMD_APPLIED, "{\"originalPostingId\":\"" + originalPostingId + "\"}", now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "补偿命令冲突");
        }
        return get(enterpriseId, warehouseId, sourceService, commandId);
    }

    /** STARTED 授权。同身份重放返回原 permit；UNKNOWN 保持占用且不得换新命令。 */
    public Map<String, Object> startPermit(String enterpriseId, String warehouseId, String sourceService, String commandId,
            String taskId, long taskEpoch, String parentId, String partId, String lineId, BigDecimal qty) {
        EffectCodes.requireAction(EffectCodes.ACTION_PICK);
        Timestamp now = Timestamp.from(clock.instant());
        EffectMapper effects = session.getMapper(EffectMapper.class);
        StockCommandMapper commands = session.getMapper(StockCommandMapper.class);
        String digest = CommandDigest.v1Parts(EffectCodes.ACTION_PICK, taskId, String.valueOf(taskEpoch),
                qty.toPlainString(), commandId);
        String effectId = ensureEffect(effects, enterpriseId, warehouseId, sourceService, EffectCodes.ACTION_PICK,
                EffectCodes.FACT_SUB_ACTION, parentId, partId, lineId, now);
        Map<String, Object> effect = effects.lockEffect(enterpriseId, warehouseId, effectId);
        Map<String, Object> reused = reuseExisting(commands, enterpriseId, warehouseId, sourceService, commandId,
                EffectCodes.ACTION_PICK, effectId, effect, null, digest);
        if (reused != null) {
            return startView(reused, commands.lockPermitByCommand(enterpriseId, warehouseId, sourceService,
                    String.valueOf(reused.get("command_id"))));
        }
        long attemptNo = acceptCommand(effects, commands, enterpriseId, warehouseId, sourceService, commandId,
                EffectCodes.ACTION_PICK, effectId, digest, null, effect, now);
        if (replayTerminal(commands, enterpriseId, warehouseId, sourceService, commandId, digest) != null) {
            return startView(commands.lockByCommand(enterpriseId, warehouseId, sourceService, commandId),
                    commands.lockPermitByCommand(enterpriseId, warehouseId, sourceService, commandId));
        }
        if (effects.casBindActive(enterpriseId, warehouseId, effectId, commandId, attemptNo, EffectCodes.STATE_STARTED,
                now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "STARTED绑定冲突");
        }
        String attemptId = UUID.randomUUID().toString();
        commands.insertPermit(UUID.randomUUID().toString(), enterpriseId, warehouseId, sourceService, commandId, effectId,
                attemptId, attemptNo, taskId, taskEpoch, EffectCodes.ACTION_PICK, digest, qty, BigDecimal.ZERO,
                qty, StockCommandCodes.PERMIT_STARTED, now, null, now);
        return startView(commands.lockByCommand(enterpriseId, warehouseId, sourceService, commandId),
                commands.lockPermitByCommand(enterpriseId, warehouseId, sourceService, commandId));
    }

    /** 发运 STARTED。效期在此时重校验；已拣事实不在这里抹掉。 */
    public Map<String, Object> startShipPermit(String enterpriseId, String warehouseId, String sourceService,
            String commandId, String taskId, long taskEpoch, String parentId, String partId, String lineId,
            BigDecimal qty, StockBucketKey bucket) {
        EffectCodes.requireAction(EffectCodes.ACTION_SHIP);
        new InventoryApplicationService(session, clock).requireLiveLotForStart(enterpriseId, warehouseId, bucket);
        Timestamp now = Timestamp.from(clock.instant());
        EffectMapper effects = session.getMapper(EffectMapper.class);
        StockCommandMapper commands = session.getMapper(StockCommandMapper.class);
        String digest = CommandDigest.v1Parts(EffectCodes.ACTION_SHIP, taskId, String.valueOf(taskEpoch),
                qty.toPlainString(), commandId);
        String effectId = ensureEffect(effects, enterpriseId, warehouseId, sourceService, EffectCodes.ACTION_SHIP,
                EffectCodes.FACT_SHIPMENT_PART, parentId, partId, lineId, now);
        Map<String, Object> effect = effects.lockEffect(enterpriseId, warehouseId, effectId);
        Map<String, Object> reused = reuseExisting(commands, enterpriseId, warehouseId, sourceService, commandId,
                EffectCodes.ACTION_SHIP, effectId, effect, null, digest);
        if (reused != null) {
            return startView(reused, commands.lockPermitByCommand(enterpriseId, warehouseId, sourceService,
                    String.valueOf(reused.get("command_id"))));
        }
        long attemptNo = acceptCommand(effects, commands, enterpriseId, warehouseId, sourceService, commandId,
                EffectCodes.ACTION_SHIP, effectId, digest, null, effect, now);
        if (replayTerminal(commands, enterpriseId, warehouseId, sourceService, commandId, digest) != null) {
            return startView(commands.lockByCommand(enterpriseId, warehouseId, sourceService, commandId),
                    commands.lockPermitByCommand(enterpriseId, warehouseId, sourceService, commandId));
        }
        if (effects.casBindActive(enterpriseId, warehouseId, effectId, commandId, attemptNo, EffectCodes.STATE_STARTED,
                now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "发运STARTED绑定冲突");
        }
        String attemptId = UUID.randomUUID().toString();
        commands.insertPermit(UUID.randomUUID().toString(), enterpriseId, warehouseId, sourceService, commandId, effectId,
                attemptId, attemptNo, taskId, taskEpoch, EffectCodes.ACTION_SHIP, digest, qty, BigDecimal.ZERO, qty,
                StockCommandCodes.PERMIT_STARTED, now, null, now);
        return startView(commands.lockByCommand(enterpriseId, warehouseId, sourceService, commandId),
                commands.lockPermitByCommand(enterpriseId, warehouseId, sourceService, commandId));
    }

    /** 分批上架：先锁定本批质量额度，再将同货主SKU批次的GOOD库存搬到存储位。 */
    public Map<String, Object> applyPutaway(String enterpriseId, String warehouseId, String commandId,
            String factParentId, String factPartId, String factLineId, String documentId, String actorId,
            String sourceExecutionId, String receiptCommandId, StockBucketKey source, StockBucketKey target, Quantity qty) {
        return applyPutaway(enterpriseId,warehouseId,commandId,factParentId,factPartId,factLineId,documentId,actorId,sourceExecutionId,receiptCommandId,source,target,qty,null);
    }

    /** 所选身份进入新命令摘要和凭证；迟到重放只恢复原命令，不重新拉回已移动身份。 */
    public Map<String,Object> applyPutaway(String enterpriseId,String warehouseId,String commandId,String factParentId,
            String factPartId,String factLineId,String documentId,String actorId,String sourceExecutionId,String receiptCommandId,
            StockBucketKey source,StockBucketKey target,Quantity qty,com.lrj.wms.contract.messaging.SerialStockSelection selection) {
        if(selection!=null) selection.requireQuantity(qty.toBigDecimal());
        String sourceService = StockCommandCodes.SOURCE_INBOUND, action = EffectCodes.ACTION_PUTAWAY;
        Timestamp now = Timestamp.from(clock.instant());
        var effects = session.getMapper(EffectMapper.class);
        var commands = session.getMapper(StockCommandMapper.class);
        String digest = CommandDigest.v1(action, documentId, source, qty.toPlainString(), target.locationId(), receiptCommandId);
        if(selection!=null) digest=CommandDigest.v1Parts("SERIAL_PUTAWAY_V1",digest,com.lrj.wms.runtime.messaging.RuntimeMessage.JSON.writeValueAsString(selection));
        String effectId = ensureEffect(effects, enterpriseId, warehouseId, sourceService, action,
                EffectCodes.FACT_SUB_ACTION, factParentId, factPartId, factLineId, now);
        var effect = effects.lockEffect(enterpriseId, warehouseId, effectId);
        var reused = reuseExisting(commands, enterpriseId, warehouseId, sourceService, commandId, action, effectId, effect, null, digest);
        if (reused != null) return view(reused);
        long attempt = acceptCommand(effects, commands, enterpriseId, warehouseId, sourceService, commandId, action,
                effectId, digest, null, effect, now);
        if (replayTerminal(commands, enterpriseId, warehouseId, sourceService, commandId, digest) != null)
            return get(enterpriseId, warehouseId, sourceService, commandId);
        if (effects.casBindActive(enterpriseId, warehouseId, effectId, commandId, attempt, EffectCodes.STATE_OPEN, now) != 1)
            throw new InventoryException("VERSION_CONFLICT", "上架效果绑定冲突");
        new com.lrj.wms.inventory.quality.ReceiptQualityStockService(session, clock).putaway(enterpriseId, warehouseId,
                receiptCommandId, documentId, source, qty.toBigDecimal());
        String operation = UUID.nameUUIDFromBytes(("PUTAWAY/" + enterpriseId + "/" + warehouseId + "/" + commandId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        new InventoryApplicationService(session, clock).move(enterpriseId, warehouseId, operation, documentId, actorId, source, target, qty, false);
        if(selection!=null) new com.lrj.wms.inventory.serial.SerialPutawayStockService(session,clock).move(enterpriseId,warehouseId,receiptCommandId,source,target,selection);
        String postingId = UUID.randomUUID().toString();
        String manifest = com.lrj.wms.runtime.messaging.RuntimeMessage.JSON.writeValueAsString(selection==null?Map.of("operationId", operation):
                Map.of("operationId",operation,"receiptCommandId",receiptCommandId,"serialSelection",selection));
        if (commands.insertPosting(postingId, enterpriseId, warehouseId, sourceService, commandId, effectId, action,
                UUID.randomUUID().toString(), "PUTAWAY", qty.toBigDecimal(), sourceExecutionId, documentId, manifest, now) != 1
                || effects.casApply(enterpriseId, warehouseId, effectId, commandId, now) != 1
                || commands.casState(enterpriseId, warehouseId, sourceService, commandId, StockCommandCodes.CMD_PENDING,
                    StockCommandCodes.CMD_APPLIED, com.lrj.wms.runtime.messaging.RuntimeMessage.JSON.writeValueAsString(Map.of("postingId", postingId)), now) != 1)
            throw new InventoryException("VERSION_CONFLICT", "上架凭证写入冲突");
        return appliedView(enterpriseId, warehouseId, sourceService, commandId, postingId);
    }

    /** 分批质检的命令、质量转桶、凭证与效果一次提交，迟到重复命令只恢复原凭证。 */
    public Map<String, Object> applyQuality(String enterpriseId, String warehouseId, String commandId,
            String factLineId, String documentId, String actorId, String sourceExecutionId, StockBucketKey hold,
            com.lrj.wms.contract.messaging.ReceiptQualityDecision decision) {
        return applyQuality(enterpriseId,warehouseId,commandId,factLineId,documentId,actorId,sourceExecutionId,hold,decision,null);
    }

    /** 身份快照参与新命令摘要与凭证，旧无身份调用保持原摘要和重放语义。 */
    public Map<String,Object> applyQuality(String enterpriseId,String warehouseId,String commandId,String factLineId,
            String documentId,String actorId,String sourceExecutionId,StockBucketKey hold,
            com.lrj.wms.contract.messaging.ReceiptQualityDecision decision,
            com.lrj.wms.contract.messaging.SerialQualityObservation observation) {
        String sourceService = StockCommandCodes.SOURCE_INBOUND, action = EffectCodes.ACTION_QUALITY;
        Timestamp now = Timestamp.from(clock.instant());
        var effects = session.getMapper(EffectMapper.class);
        var commands = session.getMapper(StockCommandMapper.class);
        String digest = CommandDigest.v1(action, documentId, hold, decision.inspectedQty().toPlainString(),
                com.lrj.wms.runtime.messaging.RuntimeMessage.JSON.writeValueAsString(decision));
        if(observation!=null) digest=CommandDigest.v1Parts("SERIAL_QUALITY_V1",digest,
                com.lrj.wms.runtime.messaging.RuntimeMessage.JSON.writeValueAsString(observation));
        String effectId = ensureEffect(effects, enterpriseId, warehouseId, sourceService, action,
                EffectCodes.FACT_SUB_ACTION, decision.receiptCommandId(), Long.toString(decision.sourceVersion()), factLineId, now);
        var effect = effects.lockEffect(enterpriseId, warehouseId, effectId);
        var reused = reuseExisting(commands, enterpriseId, warehouseId, sourceService, commandId, action, effectId, effect, null, digest);
        if (reused != null) return view(reused);
        long attempt = acceptCommand(effects, commands, enterpriseId, warehouseId, sourceService, commandId, action,
                effectId, digest, null, effect, now);
        if (replayTerminal(commands, enterpriseId, warehouseId, sourceService, commandId, digest) != null)
            return get(enterpriseId, warehouseId, sourceService, commandId);
        if (effects.casBindActive(enterpriseId, warehouseId, effectId, commandId, attempt, EffectCodes.STATE_OPEN, now) != 1)
            throw new InventoryException("VERSION_CONFLICT", "质检效果绑定冲突");
        String operation = UUID.nameUUIDFromBytes(("QUALITY/" + enterpriseId + "/" + warehouseId + "/" + commandId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        new com.lrj.wms.inventory.quality.ReceiptQualityStockService(session, clock).apply(enterpriseId, warehouseId,
                operation, documentId, actorId, hold, decision, observation);
        String postingId = UUID.randomUUID().toString();
        String manifest = com.lrj.wms.runtime.messaging.RuntimeMessage.JSON.writeValueAsString(observation==null?Map.of("operationId", operation):
                Map.of("operationId",operation,"qualityDecision",decision,"serialQualityObservation",observation));
        if (commands.insertPosting(postingId, enterpriseId, warehouseId, sourceService, commandId, effectId, action,
                UUID.randomUUID().toString(), "QUALITY", decision.inspectedQty(), sourceExecutionId, documentId, manifest, now) != 1
                || effects.casApply(enterpriseId, warehouseId, effectId, commandId, now) != 1
                || commands.casState(enterpriseId, warehouseId, sourceService, commandId, StockCommandCodes.CMD_PENDING,
                    StockCommandCodes.CMD_APPLIED, com.lrj.wms.runtime.messaging.RuntimeMessage.JSON.writeValueAsString(Map.of("postingId", postingId)), now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "质检凭证写入冲突");
        }
        return appliedView(enterpriseId, warehouseId, sourceService, commandId, postingId);
    }

    /** 拣货过账：短拣转桶。同命令重放不二次移动。 */
    public Map<String, Object> applyPick(String enterpriseId, String warehouseId, String sourceService, String commandId,
            String factParentId, String factPartId, String factLineId, String documentId, String actorId,
            String allocationId, String attemptId, StockBucketKey source, StockBucketKey target, Quantity qty) {
        return applyPick(enterpriseId, warehouseId, sourceService, commandId, factParentId, factPartId, factLineId,
                documentId, actorId, allocationId, attemptId, source, target, qty, null);
    }

    public Map<String, Object> applyPick(String enterpriseId, String warehouseId, String sourceService, String commandId,
            String factParentId, String factPartId, String factLineId, String documentId, String actorId,
            String allocationId, String attemptId, StockBucketKey source, StockBucketKey target, Quantity qty,
            String previousCommandId) {
        EffectCodes.requireAction(EffectCodes.ACTION_PICK);
        Timestamp now = Timestamp.from(clock.instant());
        EffectMapper effects = session.getMapper(EffectMapper.class);
        StockCommandMapper commands = session.getMapper(StockCommandMapper.class);
        String digest = CommandDigest.v1(EffectCodes.ACTION_PICK, documentId, source, qty.toPlainString(), commandId,
                target.locationId());
        String effectId = ensureEffect(effects, enterpriseId, warehouseId, sourceService, EffectCodes.ACTION_PICK,
                EffectCodes.FACT_SUB_ACTION, factParentId, factPartId, factLineId, now);
        Map<String, Object> effect = effects.lockEffect(enterpriseId, warehouseId, effectId);
        Map<String, Object> reused = reuseExisting(commands, enterpriseId, warehouseId, sourceService, commandId,
                EffectCodes.ACTION_PICK, effectId, effect, previousCommandId, digest);
        if (reused != null) {
            return view(reused);
        }
        long attemptNo = acceptCommand(effects, commands, enterpriseId, warehouseId, sourceService, commandId,
                EffectCodes.ACTION_PICK, effectId, digest, previousCommandId, effect, now);
        if (replayTerminal(commands, enterpriseId, warehouseId, sourceService, commandId, digest) != null) {
            return get(enterpriseId, warehouseId, sourceService, commandId);
        }
        if (effects.casBindActive(enterpriseId, warehouseId, effectId, commandId, attemptNo, EffectCodes.STATE_OPEN,
                now) != 1 && previousCommandId == null) {
            throw new InventoryException("VERSION_CONFLICT", "拣货效果冲突");
        }
        String execAttempt = UUID.randomUUID().toString();
        new InventoryApplicationService(session, clock).pickReserved(enterpriseId, warehouseId, commandId, documentId,
                actorId, allocationId, attemptId, source, target, qty);
        String postingId = UUID.randomUUID().toString();
        commands.insertPosting(postingId, enterpriseId, warehouseId, sourceService, commandId, effectId,
                EffectCodes.ACTION_PICK, execAttempt, StockCommandCodes.POSTING_PICK, qty.toBigDecimal(),
                StockCommandCodes.NO_SOURCE_EXECUTION, documentId, "{\"operationId\":\"" + commandId + "\"}", now);
        commands.insertPermit(UUID.randomUUID().toString(), enterpriseId, warehouseId, sourceService, commandId, effectId,
                execAttempt, attemptNo, factPartId, 1L, EffectCodes.ACTION_PICK, digest, qty.toBigDecimal(),
                qty.toBigDecimal(), BigDecimal.ZERO, StockCommandCodes.PERMIT_POSTED, now, now, now);
        if (effects.casApply(enterpriseId, warehouseId, effectId, commandId, now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "拣货过账冲突");
        }
        if (commands.casState(enterpriseId, warehouseId, sourceService, commandId, StockCommandCodes.CMD_PENDING,
                StockCommandCodes.CMD_APPLIED, "{\"postingId\":\"" + postingId + "\"}", now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "拣货命令冲突");
        }
        return appliedView(enterpriseId, warehouseId, sourceService, commandId, postingId);
    }

    /** 发运过账。同命令重放不二次扣减。 */
    public Map<String, Object> applyShip(String enterpriseId, String warehouseId, String sourceService, String commandId,
            String factParentId, String factPartId, String factLineId, String documentId, String actorId,
            String allocationId, String attemptId, StockBucketKey stage, Quantity qty) {
        return applyShip(enterpriseId, warehouseId, sourceService, commandId, factParentId, factPartId, factLineId,
                documentId, actorId, allocationId, attemptId, stage, qty, null);
    }

    public Map<String, Object> applyShip(String enterpriseId, String warehouseId, String sourceService, String commandId,
            String factParentId, String factPartId, String factLineId, String documentId, String actorId,
            String allocationId, String attemptId, StockBucketKey stage, Quantity qty, String previousCommandId) {
        EffectCodes.requireAction(EffectCodes.ACTION_SHIP);
        Timestamp now = Timestamp.from(clock.instant());
        EffectMapper effects = session.getMapper(EffectMapper.class);
        StockCommandMapper commands = session.getMapper(StockCommandMapper.class);
        String digest = CommandDigest.v1(EffectCodes.ACTION_SHIP, documentId, stage, qty.toPlainString(), commandId);
        String effectId = ensureEffect(effects, enterpriseId, warehouseId, sourceService, EffectCodes.ACTION_SHIP,
                EffectCodes.FACT_SHIPMENT_PART, factParentId, factPartId, factLineId, now);
        Map<String, Object> effect = effects.lockEffect(enterpriseId, warehouseId, effectId);
        Map<String, Object> reused = reuseExisting(commands, enterpriseId, warehouseId, sourceService, commandId,
                EffectCodes.ACTION_SHIP, effectId, effect, previousCommandId, digest);
        if (reused != null) {
            return view(reused);
        }
        long attemptNo = acceptCommand(effects, commands, enterpriseId, warehouseId, sourceService, commandId,
                EffectCodes.ACTION_SHIP, effectId, digest, previousCommandId, effect, now);
        if (replayTerminal(commands, enterpriseId, warehouseId, sourceService, commandId, digest) != null) {
            return get(enterpriseId, warehouseId, sourceService, commandId);
        }
        if (effects.casBindActive(enterpriseId, warehouseId, effectId, commandId, attemptNo, EffectCodes.STATE_OPEN,
                now) != 1 && previousCommandId == null) {
            throw new InventoryException("VERSION_CONFLICT", "发运效果冲突");
        }
        String execAttempt = UUID.randomUUID().toString();
        new InventoryApplicationService(session, clock).shipPicked(enterpriseId, warehouseId, commandId, documentId,
                actorId, allocationId, attemptId, stage, qty);
        String postingId = UUID.randomUUID().toString();
        commands.insertPosting(postingId, enterpriseId, warehouseId, sourceService, commandId, effectId,
                EffectCodes.ACTION_SHIP, execAttempt, StockCommandCodes.POSTING_SHIPMENT, qty.toBigDecimal(),
                StockCommandCodes.NO_SOURCE_EXECUTION, documentId, "{\"operationId\":\"" + commandId + "\"}", now);
        if (effects.casApply(enterpriseId, warehouseId, effectId, commandId, now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "发运过账冲突");
        }
        if (commands.casState(enterpriseId, warehouseId, sourceService, commandId, StockCommandCodes.CMD_PENDING,
                StockCommandCodes.CMD_APPLIED, "{\"postingId\":\"" + postingId + "\"}", now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "发运命令冲突");
        }
        return appliedView(enterpriseId, warehouseId, sourceService, commandId, postingId);
    }

    /**
     * 出库运行命令使用完整原始身份摘要，T2同时提交预占、余额、流水及来源执行凭证。
     * CANCEL是独立释放业务效果，APPLIED回执不等于将另一条PICK/SHIP命令取消。
     */
    public Map<String, Object> applyOutbound(String enterpriseId, String warehouseId, String commandId, String action,
            String factParentId, String factPartId, String factLineId, String actorId, String sourceExecutionId,
            String reservationOrderLineId, com.lrj.wms.contract.messaging.StockPostingContext context,
            Quantity qty, String previousCommandId) {
        if (!java.util.Set.of("PICK", "SHIP", "CANCEL").contains(action))
            throw new InventoryException("INVALID_RESERVATION_CONTEXT", "未知出库动作");
        context.requireForAction(action);
        for (String id : new String[] {commandId, factParentId, factPartId, factLineId, sourceExecutionId, reservationOrderLineId})
            if (id == null || id.isBlank() || id.length() > 64) throw new InventoryException("INVALID_RESERVATION_CONTEXT", "原出库身份缺失或超长");
        String sourceService = StockCommandCodes.SOURCE_OUTBOUND;
        var json = com.lrj.wms.runtime.messaging.RuntimeMessage.JSON;
        String digest = com.lrj.wms.runtime.messaging.RuntimeMessage.contentHash(json.writeValueAsString(
                java.util.List.of("OUTBOUND_POSTING_V1", enterpriseId, warehouseId, action, factParentId, factPartId,
                        factLineId, sourceExecutionId, reservationOrderLineId, context, qty.toBigDecimal().stripTrailingZeros().toPlainString())));
        Timestamp now = Timestamp.from(clock.instant());
        var effects = session.getMapper(EffectMapper.class); var commands = session.getMapper(StockCommandMapper.class);
        String effectId = ensureEffect(effects, enterpriseId, warehouseId, sourceService, action,
                "SHIP".equals(action) ? EffectCodes.FACT_SHIPMENT_PART : EffectCodes.FACT_SUB_ACTION,
                factParentId, factPartId, factLineId, now);
        var effect = effects.lockEffect(enterpriseId, warehouseId, effectId);
        var reused = reuseExisting(commands, enterpriseId, warehouseId, sourceService, commandId, action, effectId, effect, previousCommandId, digest);
        if (reused != null) {
            if (!digest.equals(reused.get("payload_digest"))) throw new InventoryException("COMMAND_CONFLICT", "同出库事实不能更换预占或过账维度");
            return view(reused);
        }
        long attempt = acceptCommand(effects, commands, enterpriseId, warehouseId, sourceService, commandId, action,
                effectId, digest, previousCommandId, effect, now);
        if (replayTerminal(commands, enterpriseId, warehouseId, sourceService, commandId, digest) != null)
            return get(enterpriseId, warehouseId, sourceService, commandId);
        if (effects.casBindActive(enterpriseId, warehouseId, effectId, commandId, attempt, EffectCodes.STATE_OPEN, now) != 1)
            throw new InventoryException("VERSION_CONFLICT", "出库效果绑定冲突");
        var source = StockBucketKey.of(enterpriseId, warehouseId, context.ownerId(), context.sourceLocationId(),
                context.skuId(), context.lotId(), context.qualityCode());
        var target = context.targetLocationId() == null ? null : StockBucketKey.of(enterpriseId, warehouseId, context.ownerId(),
                context.targetLocationId(), context.skuId(), context.lotId(), context.qualityCode());
        // 库存流水operationId跨来源共享唯一空间，按企业/仓/服务/原命令生成稳定身份。
        String operation = com.lrj.wms.runtime.messaging.RuntimeMessage.hash(json.writeValueAsString(
                java.util.List.of(sourceService, enterpriseId, warehouseId, commandId)));
        new InventoryApplicationService(session, clock).postOutboundReservation(enterpriseId, warehouseId, operation,
                context.documentId(), actorId, action, context.allocationId(), context.allocationAttemptId(),
                reservationOrderLineId, source, target, qty);
        String postingId = UUID.randomUUID().toString();
        String postingType = "SHIP".equals(action) ? StockCommandCodes.POSTING_SHIPMENT : "CANCEL".equals(action) ? "RELEASE" : StockCommandCodes.POSTING_PICK;
        String manifest = json.writeValueAsString(Map.of("operationId", operation, "reservationOrderLineId", reservationOrderLineId,
                "allocationId", context.allocationId(), "allocationAttemptId", context.allocationAttemptId()));
        if (commands.insertPosting(postingId, enterpriseId, warehouseId, sourceService, commandId, effectId, action,
                UUID.randomUUID().toString(), postingType, qty.toBigDecimal(), sourceExecutionId, context.documentId(), manifest, now) != 1
                || effects.casApply(enterpriseId, warehouseId, effectId, commandId, now) != 1
                || commands.casState(enterpriseId, warehouseId, sourceService, commandId, StockCommandCodes.CMD_PENDING,
                    StockCommandCodes.CMD_APPLIED, json.writeValueAsString(Map.of("postingId", postingId)), now) != 1)
            throw new InventoryException("VERSION_CONFLICT", "出库凭证与命令必须同时提交");
        return appliedView(enterpriseId, warehouseId, sourceService, commandId, postingId);
    }

    /** 设备未知：permit/效果进入 UNKNOWN，不释放占用，不能普通取消。 */
    public Map<String, Object> markUnknown(String enterpriseId, String warehouseId, String sourceService, String commandId) {
        Timestamp now = Timestamp.from(clock.instant());
        EffectMapper effects = session.getMapper(EffectMapper.class);
        StockCommandMapper commands = session.getMapper(StockCommandMapper.class);
        Map<String, Object> permit = commands.lockPermitByCommand(enterpriseId, warehouseId, sourceService, commandId);
        if (permit == null) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "执行授权不存在");
        }
        String permitState = String.valueOf(permit.get("state"));
        if (StockCommandCodes.PERMIT_UNKNOWN.equals(permitState)) {
            return startView(commands.lockByCommand(enterpriseId, warehouseId, sourceService, commandId), permit);
        }
        if (!StockCommandCodes.PERMIT_STARTED.equals(permitState)) {
            throw new InventoryException("INVALID_STATE", "仅STARTED可以记UNKNOWN");
        }
        Map<String, Object> command = commands.lockByCommand(enterpriseId, warehouseId, sourceService, commandId);
        if (command == null) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "命令不存在");
        }
        if (effects.casBindActive(enterpriseId, warehouseId, String.valueOf(command.get("business_effect_key")), commandId,
                longValue(command.get("attempt_no")), EffectCodes.STATE_UNKNOWN, now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "UNKNOWN绑定冲突");
        }
        if (commands.casPermitState(enterpriseId, warehouseId, sourceService, commandId, StockCommandCodes.PERMIT_STARTED,
                StockCommandCodes.PERMIT_UNKNOWN, now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "UNKNOWN授权冲突");
        }
        return startView(commands.lockByCommand(enterpriseId, warehouseId, sourceService, commandId),
                commands.lockPermitByCommand(enterpriseId, warehouseId, sourceService, commandId));
    }

    /** 恢复查询，不改变状态。 */
    public Map<String, Object> get(String enterpriseId, String warehouseId, String sourceService, String commandId) {
        Map<String, Object> row = session.getMapper(StockCommandMapper.class).getByCommand(enterpriseId, warehouseId,
                sourceService, commandId);
        if (row == null) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "命令不存在");
        }
        return view(row);
    }

    private Map<String, Object> reuseExisting(StockCommandMapper commands, String enterpriseId, String warehouseId,
            String sourceService, String commandId, String action, String effectId, Map<String, Object> effect,
            String previousCommandId, String digest) {
        Map<String, Object> same = commands.getByCommand(enterpriseId, warehouseId, sourceService, commandId);
        if (same != null) {
            if (!digest.equals(String.valueOf(same.get("payload_digest")))) {
                throw new InventoryException("COMMAND_CONFLICT", "同命令不同内容");
            }
            return same;
        }
        if (effect.get("applied_command_id") != null) {
            return commands.getByCommand(enterpriseId, warehouseId, sourceService,
                    String.valueOf(effect.get("applied_command_id")));
        }
        if (previousCommandId != null && !previousCommandId.isBlank()) {
            return null;
        }
        Map<String, Object> latest = commands.findLatestByEffect(enterpriseId, warehouseId, sourceService, effectId, action);
        if (latest != null) {
            return latest;
        }
        Object active = effect.get("active_command_id");
        if (active != null) {
            return commands.getByCommand(enterpriseId, warehouseId, sourceService, String.valueOf(active));
        }
        return null;
    }

    private long acceptCommand(EffectMapper effects, StockCommandMapper commands, String enterpriseId, String warehouseId,
            String sourceService, String commandId, String action, String effectId, String digest, String previousCommandId,
            Map<String, Object> effect, Timestamp now) {
        if (previousCommandId == null || previousCommandId.isBlank()) {
            commands.insertIgnore(enterpriseId, warehouseId, sourceService, commandId, action, effectId, commandId, 1L,
                    null, digest, CommandDigest.VERSION_1, StockCommandCodes.CMD_PENDING, now);
            return 1L;
        }
        if (!EffectCodes.allowsNewAttempt(String.valueOf(effect.get("state")))) {
            throw new InventoryException("STALE_EXECUTION_ATTEMPT", "未安全关闭不得发新尝试");
        }
        if (!previousCommandId.equals(String.valueOf(effect.get("active_command_id")))) {
            throw new InventoryException("STALE_EXECUTION_ATTEMPT", "必须引用已安全关闭的上一命令");
        }
        Map<String, Object> previous = commands.lockByCommand(enterpriseId, warehouseId, sourceService, previousCommandId);
        if (previous == null || previous.get("safe_close_id") == null
                || String.valueOf(previous.get("safe_close_id")).isBlank()) {
            throw new InventoryException("STALE_EXECUTION_ATTEMPT", "上一命令缺少本地安全关闭证据");
        }
        long nextNo = longValue(effect.get("attempt_no")) + 1;
        if (effects.casNextAttempt(enterpriseId, warehouseId, effectId, nextNo, commandId, EffectCodes.STATE_OPEN,
                String.valueOf(effect.get("state")), longValue(effect.get("version")), now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "并发发放尝试失败");
        }
        commands.insertIgnore(enterpriseId, warehouseId, sourceService, commandId, action, effectId, commandId, nextNo,
                previousCommandId, digest, CommandDigest.VERSION_1, StockCommandCodes.CMD_PENDING, now);
        return nextNo;
    }

    private Map<String, Object> replayTerminal(StockCommandMapper commands, String enterpriseId, String warehouseId,
            String sourceService, String commandId, String digest) {
        Map<String, Object> row = commands.lockByCommand(enterpriseId, warehouseId, sourceService, commandId);
        if (row == null) {
            throw new InventoryException("VERSION_CONFLICT", "命令受理竞争");
        }
        if (!digest.equals(String.valueOf(row.get("payload_digest")))) {
            throw new InventoryException("COMMAND_CONFLICT", "同命令不同内容");
        }
        String state = String.valueOf(row.get("state"));
        if (StockCommandCodes.CMD_CANCELLED.equals(state) || StockCommandCodes.CMD_APPLIED.equals(state)
                || StockCommandCodes.CMD_REJECTED.equals(state)) {
            return row;
        }
        return null;
    }

    private String ensureEffect(EffectMapper effects, String enterpriseId, String warehouseId, String sourceService,
            String action, String factType, String factParentId, String factPartId, String factLineId, Timestamp now) {
        String candidate = UUID.randomUUID().toString();
        effects.insertEffect(candidate, enterpriseId, warehouseId, sourceService, action, factType, factParentId, factPartId,
                factLineId, EffectCodes.STATE_REGISTERED, now);
        String effectId = effects.findEffectId(enterpriseId, warehouseId, sourceService, action, factType, factParentId,
                factPartId, factLineId);
        if (effectId == null) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "效果登记失败");
        }
        return effectId;
    }

    private static void refuseIfOccupying(Map<String, Object> permit) {
        if (permit == null) {
            return;
        }
        String state = String.valueOf(permit.get("state"));
        if (StockCommandCodes.PERMIT_STARTED.equals(state)) {
            throw new InventoryException("PERMIT_STARTED", "已开始执行不得普通取消或安全关闭");
        }
        if (StockCommandCodes.PERMIT_UNKNOWN.equals(state)) {
            throw new InventoryException("PERMIT_UNKNOWN", "未知结果保持占用，不得释放");
        }
    }

    private static Map<String, Object> startView(Map<String, Object> command, Map<String, Object> permit) {
        Map<String, Object> body = view(command);
        if (permit != null) {
            body.put("permitId", permit.get("permit_id"));
            body.put("permitState", permit.get("state"));
            body.put("taskId", permit.get("source_task_id"));
            body.put("taskEpoch", permit.get("source_task_epoch"));
        }
        return body;
    }

    private Map<String, Object> appliedView(String enterpriseId, String warehouseId, String sourceService,
            String commandId, String postingId) {
        Map<String, Object> body = get(enterpriseId, warehouseId, sourceService, commandId);
        body.put("postingId", postingId);
        return body;
    }

    private static Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandId", row.get("command_id"));
        body.put("state", row.get("state"));
        body.put("businessEffectKey", row.get("business_effect_key"));
        body.put("attemptNo", row.get("attempt_no"));
        body.put("executionAttemptId", row.get("execution_attempt_id"));
        if (row.get("safe_close_id") != null) {
            body.put("safeCloseRef", row.get("safe_close_id"));
        }
        return body;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
