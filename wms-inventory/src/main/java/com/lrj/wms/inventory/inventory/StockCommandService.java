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
 * 晚到命令只能读到墓碑，不得入账。
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
        EffectCodes.requireAction(EffectCodes.ACTION_RECEIVE);
        Timestamp now = Timestamp.from(clock.instant());
        EffectMapper effects = session.getMapper(EffectMapper.class);
        StockCommandMapper commands = session.getMapper(StockCommandMapper.class);
        String digest = CommandDigest.v1(EffectCodes.ACTION_RECEIVE, documentId, bucket, qty.toPlainString(), commandId);
        String effectId = ensureEffect(effects, enterpriseId, warehouseId, sourceService, EffectCodes.ACTION_RECEIVE,
                EffectCodes.FACT_RECEIPT_PART, factParentId, factPartId, factLineId, now);
        Map<String, Object> effect = effects.lockEffect(enterpriseId, warehouseId, effectId);
        if (effect.get("applied_command_id") != null
                && !commandId.equals(String.valueOf(effect.get("applied_command_id")))) {
            throw new InventoryException("EFFECT_ALREADY_APPLIED", "已过账效果不得再受理其他命令");
        }
        Map<String, Object> existing = acceptOrReplay(commands, enterpriseId, warehouseId, sourceService, commandId,
                EffectCodes.ACTION_RECEIVE, effectId, digest);
        if (existing != null) {
            return view(existing);
        }
        if (effects.casBindActive(enterpriseId, warehouseId, effectId, commandId, 1, EffectCodes.STATE_OPEN, now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "效果活动命令冲突");
        }
        String attemptId = UUID.randomUUID().toString();
        new InventoryApplicationService(session, clock).receive(enterpriseId, warehouseId, commandId, documentId, actorId,
                bucket, qty);
        String postingId = UUID.randomUUID().toString();
        commands.insertPosting(postingId, enterpriseId, warehouseId, sourceService, commandId, effectId,
                EffectCodes.ACTION_RECEIVE, attemptId, StockCommandCodes.POSTING_RECEIPT, qty.toBigDecimal(),
                sourceExecutionId == null || sourceExecutionId.isBlank() ? StockCommandCodes.NO_SOURCE_EXECUTION
                        : sourceExecutionId,
                documentId, "{\"operationId\":\"" + commandId + "\"}", now);
        commands.insertPermit(UUID.randomUUID().toString(), enterpriseId, warehouseId, sourceService, commandId, effectId,
                attemptId, 1L, documentId, 1L, EffectCodes.ACTION_RECEIVE, digest, qty.toBigDecimal(), qty.toBigDecimal(),
                BigDecimal.ZERO, StockCommandCodes.PERMIT_POSTED, now, now, now);
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
        if (permit != null && StockCommandCodes.PERMIT_STARTED.equals(String.valueOf(permit.get("state")))) {
            throw new InventoryException("PERMIT_STARTED", "已开始执行不得普通取消");
        }
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

    /** 恢复查询，不改变状态。 */
    public Map<String, Object> get(String enterpriseId, String warehouseId, String sourceService, String commandId) {
        Map<String, Object> row = session.getMapper(StockCommandMapper.class).getByCommand(enterpriseId, warehouseId,
                sourceService, commandId);
        if (row == null) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "命令不存在");
        }
        return view(row);
    }

    private Map<String, Object> acceptOrReplay(StockCommandMapper commands, String enterpriseId, String warehouseId,
            String sourceService, String commandId, String action, String effectId, String digest) {
        Timestamp now = Timestamp.from(clock.instant());
        commands.insertIgnore(enterpriseId, warehouseId, sourceService, commandId, action, effectId, commandId, 1L, null,
                digest, CommandDigest.VERSION_1, StockCommandCodes.CMD_PENDING, now);
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

    private static Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandId", row.get("command_id"));
        body.put("state", row.get("state"));
        body.put("businessEffectKey", row.get("business_effect_key"));
        body.put("attemptNo", row.get("attempt_no"));
        return body;
    }
}
