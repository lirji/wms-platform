package com.lrj.wms.inventory.inventory;

import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.infrastructure.OutboxMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

/**
 * 按单个物理数据源领取、投递、重试或隔离 Outbox。
 * 不绑定 Kafka；未配置真实 transport 时不得把 PUBLISHED 当成外部已投递。
 */
public final class OutboxPublisher {
    static final int BATCH_SIZE = 32;
    static final int MAX_CLAIMS = 8;
    static final Duration LEASE = Duration.ofSeconds(30);

    private final SqlSessionFactory sessions;
    private final OutboxTransport transport;
    private final Clock clock;
    private final OutboxBudget budget;

    public OutboxPublisher(SqlSessionFactory sessions, OutboxTransport transport, Clock clock) {
        this(sessions, transport, clock, OutboxBudget.defaults());
    }

    /** 配置在启动时校验并作为一次执行的不可变快照。 */
    public OutboxPublisher(SqlSessionFactory sessions, OutboxTransport transport, Clock clock, OutboxBudget budget) {
        this.budget = budget;
        this.sessions = sessions;
        this.transport = transport;
        this.clock = clock;
    }

    /** 领取一批到期事件并尝试投递，返回成功标记 PUBLISHED 的条数。 */
    public int publishDue() {
        List<Claimed> claimed = claimDue();
        int published = 0;
        for (Claimed item : claimed) {
            if (item.claimEpoch() >= budget.maxClaims()) {
                finish(item, InventoryCodes.OUTBOX_ISOLATED, null);
                continue;
            }
            try {
                transport.publish(item.record());
                finish(item, InventoryCodes.OUTBOX_PUBLISHED, null);
                published++;
            } catch (OutboxIsolateException isolated) {
                finish(item, InventoryCodes.OUTBOX_ISOLATED, null);
            } catch (RuntimeException retryable) {
                finish(item, InventoryCodes.OUTBOX_PENDING, budget.retryDelay(item.claimEpoch()));
            }
        }
        return published;
    }

    private List<Claimed> claimDue() {
        Timestamp now = Timestamp.from(clock.instant());
        Timestamp leaseUntil = Timestamp.from(clock.instant().plusSeconds(budget.leaseSeconds()));
        List<Claimed> claimed = new ArrayList<>();
        try (SqlSession session = sessions.openSession(false)) {
            OutboxMapper mapper = session.getMapper(OutboxMapper.class);
            for (Map<String, Object> row : mapper.lockDue(now, budget.batchSize())) {
                long epoch = ((Number) row.get("claim_epoch")).longValue();
                if (mapper.claim(String.valueOf(row.get("event_id")), epoch, leaseUntil, now) != 1) {
                    continue;
                }
                claimed.add(new Claimed(toRecord(row), epoch + 1));
            }
            session.commit();
        }
        return claimed;
    }

    private void finish(Claimed item, String status, Duration retryDelay) {
        Timestamp now = Timestamp.from(clock.instant());
        try (SqlSession session = sessions.openSession(false)) {
            OutboxMapper mapper = session.getMapper(OutboxMapper.class);
            int updated;
            if (InventoryCodes.OUTBOX_PUBLISHED.equals(status)) {
                updated = mapper.markPublished(item.record().eventId(), item.claimEpoch(), now);
            } else if (InventoryCodes.OUTBOX_ISOLATED.equals(status)) {
                updated = mapper.markIsolated(item.record().eventId(), item.claimEpoch(), now);
            } else {
                Timestamp next = Timestamp.from(clock.instant().plus(retryDelay == null ? Duration.ZERO : retryDelay));
                updated = mapper.markRetry(item.record().eventId(), item.claimEpoch(), next, now);
            }
            if (updated != 1) {
                session.rollback();
                return;
            }
            session.commit();
        }
    }

    private static OutboxRecord toRecord(Map<String, Object> row) {
        return new OutboxRecord(String.valueOf(row.get("event_id")), String.valueOf(row.get("enterprise_id")),
                String.valueOf(row.get("warehouse_id")), String.valueOf(row.get("aggregate_type")),
                String.valueOf(row.get("aggregate_id")), ((Number) row.get("aggregate_version")).longValue(),
                String.valueOf(row.get("event_type")), String.valueOf(row.get("operation_id")),
                String.valueOf(row.get("payload")));
    }

    private record Claimed(OutboxRecord record, long claimEpoch) {
    }
}
