package com.lrj.wms.inventory.count;

import com.lrj.wms.inventory.inventory.InventoryException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.ibatis.session.SqlSessionFactory;

/** 审批后的逐行恢复：持久化领取、代际防旧写、独立事务及有界退避。 */
public final class CountApplyRecovery {
    private static final int BATCH_SIZE = 20;
    private static final int LEASE_SECONDS = 30;
    private final SqlSessionFactory sessions;
    private final Clock clock;
    private final com.lrj.wms.inventory.serial.SerialCountRegistryPort registry;

    public CountApplyRecovery(SqlSessionFactory sessions, Clock clock) {
        this(sessions,clock,null);
    }
    /** 登记仅交给逐身份协调器在事务外调用；整行应用只能消费本地凭证。 */
    public CountApplyRecovery(SqlSessionFactory sessions,Clock clock,com.lrj.wms.inventory.serial.SerialCountRegistryPort registry) {
        this.sessions=sessions;this.clock=clock;this.registry=registry;
    }

    /** 仅调整明确指定的已审批计划；解冻仍由原用例逐行检查全部终态。 */
    public Report execute(String enterpriseId, String warehouseId, String planId) {
        var serial=new CountSerialRecovery(sessions,clock,registry).execute(enterpriseId,warehouseId,planId);
        int applied = 0;
        int failed = serial.failed();
        for (int n = 0; n < BATCH_SIZE && !Thread.currentThread().isInterrupted(); n++) {
            Claim claim = claim(enterpriseId, warehouseId, planId);
            if (claim == null) break;
            try (var session = sessions.openSession(false)) {
                com.lrj.wms.inventory.serial.SerialRecoveryService.requireWritable(session,enterpriseId,warehouseId);
                CountMapper mapper = session.getMapper(CountMapper.class);
                requireApproved(mapper.lockPlan(enterpriseId, warehouseId, planId));
                var line = mapper.lockLine(enterpriseId, warehouseId, planId, claim.lineId());
                if (line == null || ((Number) line.get("recovery_epoch")).longValue() != claim.epoch()) {
                    session.rollback();
                    continue;
                }
                // 稳定操作身份不使用调度执行号；重启或重复调度仍对应同一次已审批调整。
                String operationId = UUID.nameUUIDFromBytes((enterpriseId + "\u0000" + warehouseId + "\u0000"
                        + planId + "\u0000" + claim.lineId()).getBytes(StandardCharsets.UTF_8)).toString();
                new CountSerialAdjustmentService(session,clock).apply(enterpriseId,warehouseId,planId,
                        claim.lineId(),operationId,"job:countApplyRecovery");
                session.commit();
                applied++;
            } catch (RuntimeException failure) {
                // try-with-resources 已回滚业务事务；失败进度在新事务保存，避免连成功行一起回滚。
                String code = failure instanceof InventoryException business ? business.code() : "COUNT_RECOVERY_FAILED";
                long delayMillis = Math.min(60_000L, 1000L << Math.min(claim.attempt(), 6))
                        + ThreadLocalRandom.current().nextLong(501);
                try (var session = sessions.openSession(false)) {
                    com.lrj.wms.inventory.serial.SerialRecoveryService.requireWritable(session,enterpriseId,warehouseId);
                    CountMapper mapper = session.getMapper(CountMapper.class);
                    mapper.lockPlan(enterpriseId, warehouseId, planId);
                    mapper.failRecovery(enterpriseId, warehouseId, claim.lineId(), claim.epoch(),
                            Timestamp.from(clock.instant().plusMillis(delayMillis)), code);
                    session.commit();
                }
                failed++;
            }
        }
        return new Report(applied, failed);
    }

    private Claim claim(String enterpriseId, String warehouseId, String planId) {
        try (var session = sessions.openSession(false)) {
            // 先锁仓路由再锁计划；迁移切换后的旧执行器不能新增租约或写失败进度。
            com.lrj.wms.inventory.serial.SerialRecoveryService.requireWritable(session,enterpriseId,warehouseId);
            CountMapper mapper = session.getMapper(CountMapper.class);
            Map<String, Object> plan = mapper.lockPlan(enterpriseId, warehouseId, planId);
            if (plan != null && CountService.COMPLETED.equals(plan.get("status"))) return null;
            requireApproved(plan);
            var row = mapper.nextRecovery(enterpriseId, warehouseId, planId, Timestamp.from(clock.instant()));
            if (row == null) return null;
            String lineId = String.valueOf(row.get("id"));
            long epoch = ((Number) row.get("recovery_epoch")).longValue();
            if (mapper.claimRecovery(enterpriseId, warehouseId, lineId, epoch,
                    Timestamp.from(clock.instant().plusSeconds(LEASE_SECONDS))) != 1) {
                throw new InventoryException("VERSION_CONFLICT", "盘点恢复领取竞争");
            }
            session.commit();
            return new Claim(lineId, epoch + 1, ((Number) row.get("recovery_attempts")).intValue() + 1);
        }
    }

    private static void requireApproved(Map<String, Object> plan) {
        if (plan == null || plan.get("approval_id") == null || plan.get("approved_by") == null
                || !(CountService.APPROVED.equals(plan.get("status")) || CountService.APPLYING.equals(plan.get("status")))) {
            throw new InventoryException("COUNT_STATE_CONFLICT", "恢复任务只能处理已审批计划");
        }
    }

    private record Claim(String lineId, long epoch, int attempt) { }
    public record Report(int applied, int failed) { }
}
