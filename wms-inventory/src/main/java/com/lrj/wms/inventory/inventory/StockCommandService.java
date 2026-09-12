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
        return get(enterpriseId, warehouseId, sourceService, commandId);
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
        if (reused != null) {
            return view(reused);
        }
        Map<String, Object> original = commands.lockPosting(enterpriseId, warehouseId, originalPostingId);
        if (original == null) {
            throw new InventoryException("ORIGINAL_POSTING_MISSING", "补偿必须引用原过账凭证");
        }
        if (commands.addReversed(enterpriseId, warehouseId, originalPostingId, qty.toBigDecimal(), now) != 1) {
            throw new InventoryException("OVER_REVERSE", "逆向累计超过原凭证可逆量");
        }
        commands.insertIgnore(enterpriseId, warehouseId, sourceService, commandId, EffectCodes.ACTION_COMPENSATE, effectId,
                commandId, 1L, null, digest, CommandDigest.VERSION_1, StockCommandCodes.CMD_PENDING, now);
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

    /** 拣货过账：短拣转桶。同命令重放不二次移动。 */
    public Map<String, Object> applyPick(String enterpriseId, String warehouseId, String sourceService, String commandId,
            String factParentId, String factPartId, String factLineId, String documentId, String actorId,
            String allocationId, String attemptId, StockBucketKey source, StockBucketKey target, Quantity qty) {
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
                EffectCodes.ACTION_PICK, effectId, effect, null, digest);
        if (reused != null) {
            return view(reused);
        }
        long attemptNo = acceptCommand(effects, commands, enterpriseId, warehouseId, sourceService, commandId,
                EffectCodes.ACTION_PICK, effectId, digest, null, effect, now);
        if (replayTerminal(commands, enterpriseId, warehouseId, sourceService, commandId, digest) != null) {
            return get(enterpriseId, warehouseId, sourceService, commandId);
        }
        if (effects.casBindActive(enterpriseId, warehouseId, effectId, commandId, attemptNo, EffectCodes.STATE_OPEN,
                now) != 1) {
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
        EffectCodes.requireAction(EffectCodes.ACTION_SHIP);
        Timestamp now = Timestamp.from(clock.instant());
        EffectMapper effects = session.getMapper(EffectMapper.class);
        StockCommandMapper commands = session.getMapper(StockCommandMapper.class);
        String digest = CommandDigest.v1(EffectCodes.ACTION_SHIP, documentId, stage, qty.toPlainString(), commandId);
        String effectId = ensureEffect(effects, enterpriseId, warehouseId, sourceService, EffectCodes.ACTION_SHIP,
                EffectCodes.FACT_SHIPMENT_PART, factParentId, factPartId, factLineId, now);
        Map<String, Object> effect = effects.lockEffect(enterpriseId, warehouseId, effectId);
        Map<String, Object> reused = reuseExisting(commands, enterpriseId, warehouseId, sourceService, commandId,
                EffectCodes.ACTION_SHIP, effectId, effect, null, digest);
        if (reused != null) {
            return view(reused);
        }
        long attemptNo = acceptCommand(effects, commands, enterpriseId, warehouseId, sourceService, commandId,
                EffectCodes.ACTION_SHIP, effectId, digest, null, effect, now);
        if (replayTerminal(commands, enterpriseId, warehouseId, sourceService, commandId, digest) != null) {
            return get(enterpriseId, warehouseId, sourceService, commandId);
        }
        if (effects.casBindActive(enterpriseId, warehouseId, effectId, commandId, attemptNo, EffectCodes.STATE_OPEN,
                now) != 1) {
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
