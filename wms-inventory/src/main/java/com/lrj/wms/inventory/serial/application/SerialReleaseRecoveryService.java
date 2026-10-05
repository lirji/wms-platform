package com.lrj.wms.inventory.serial.application;

import com.lrj.wms.inventory.inventory.domain.InventoryException;
import com.lrj.wms.inventory.serial.persistence.SerialReleaseMapper;
import com.lrj.wms.inventory.serial.registry.error.SerialRegistryConflictException;
import com.lrj.wms.inventory.serial.registry.port.SerialReleaseRegistryPort;
import com.lrj.wms.inventory.serial.registry.protocol.SerialRegistryProof;
import com.lrj.wms.runtime.messaging.protocol.RuntimeMessage;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** 扣减已提交后只重放原释放事实；远程调用期间不持有本地连接，旧执行器不能覆盖新领取结果。 */
public final class SerialReleaseRecoveryService {
    private final SqlSessionFactory sessions;
    private final Clock clock;
    private final SerialReleaseRegistryPort registry;

    public SerialReleaseRecoveryService(
            SqlSessionFactory sessions, Clock clock, SerialReleaseRegistryPort registry) {
        this.sessions = sessions;
        this.clock = clock;
        this.registry = registry;
    }

    /** 必须与SEALED及源流水同事务调用；重复封闭只能核对已存在的原事实，不能猜测历史回填。 */
    static void stage(
            SqlSession session,
            Clock clock,
            String e,
            String w,
            Map<String, Object> local,
            String transfer,
            long epoch,
            String ref,
            String target,
            boolean replay) {
        String serial = String.valueOf(local.get("serial_id"));
        String id =
                com.lrj.wms.runtime.messaging.protocol.RuntimeMessage.hash(
                        RuntimeMessage.JSON.writeValueAsString(
                                List.of("SOURCE_RELEASE", e, w, serial, transfer)));
        String hash =
                com.lrj.wms.runtime.messaging.protocol.RuntimeMessage.hash(
                        RuntimeMessage.JSON.writeValueAsString(
                                List.of(
                                        e,
                                        w,
                                        serial,
                                        local.get("sku_id"),
                                        local.get("balance_id"),
                                        transfer,
                                        epoch,
                                        ref)));
        // 旧意图摘要保持不变；只有新公开调拨扩展目的仓，禁止给旧封闭猜测补齐目的。
        if (target != null)
            hash =
                    com.lrj.wms.runtime.messaging.protocol.RuntimeMessage.hash(
                            RuntimeMessage.JSON.writeValueAsString(List.of(hash, target)));
        var mapper = session.getMapper(SerialReleaseMapper.class);
        if (!replay) {
            var row =
                    new HashMap<String, Object>(
                            Map.of(
                                    "id",
                                    id,
                                    "e",
                                    e,
                                    "w",
                                    w,
                                    "serial",
                                    serial,
                                    "sku",
                                    local.get("sku_id"),
                                    "transfer",
                                    transfer,
                                    "ref",
                                    ref,
                                    "epoch",
                                    epoch,
                                    "hash",
                                    hash));
            row.put("target", target);
            row.put("now", Timestamp.from(clock.instant()));
            mapper.insert(row);
        }
        var original = mapper.lock(e, w, id);
        if (original == null)
            throw new InventoryException(
                    "SERIAL_RELEASE_CONTEXT_REQUIRED", "历史封闭缺少持久释放事实，需要人工核实，禁止推测补发");
        if (!hash.equals(original.get("context_hash")))
            throw new InventoryException("SERIAL_OPERATION_MISMATCH", "必须保留原封闭身份、源桶、释放引用和代际");
    }

    /** 每次最多20条，10秒内停止领取；单次HTTP另有1500ms截止，自动最多12次后隔离。 */
    public SerialRecoveryService.Report execute(String e, String w) {
        if (registry == null) throw new IllegalStateException("未配置真实源释放登记调用器");
        int done = 0, failed = 0;
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        for (int n = 0;
                n < 20 && System.nanoTime() < deadline && !Thread.currentThread().isInterrupted();
                n++) {
            var intent = claim(e, w);
            if (intent == null) break;
            try {
                String sku = (String) intent.get("sku_id"),
                        serial = (String) intent.get("serial_id"),
                        transfer = (String) intent.get("transfer_id"),
                        ref = (String) intent.get("release_ref");
                if (intent.get("target_warehouse_id") != null)
                    registry.prepare(
                            e,
                            sku,
                            serial,
                            transfer,
                            w,
                            (String) intent.get("target_warehouse_id"),
                            (String) intent.get("id"),
                            number(intent, "from_epoch"));
                // 原释放即使目的仓已激活或开始下一轮转移仍有效，不能拿当前local_serial重构或否定历史。
                var result =
                        registry.release(
                                e, sku, serial, transfer, w, ref, number(intent, "from_epoch"));
                SerialRegistryProof.requireRelease(
                        result, e, w, sku, serial, transfer, ref, number(intent, "from_epoch"));
                if (finish(e, w, intent, "DONE", null, 0)) done++;
            } catch (RuntimeException error) {
                String code =
                        error instanceof SerialRegistryConflictException conflict
                                ? conflict.code()
                                : error instanceof InventoryException inventory
                                        ? inventory.code()
                                        : "REGISTRY_UNAVAILABLE";
                boolean permanent =
                        error instanceof SerialRegistryConflictException
                                && !Set.of("SERIAL_STATE_CONFLICT", "VERSION_CONFLICT")
                                        .contains(code);
                long attempts = number(intent, "attempts");
                long delay =
                        Math.min(300000L, 1000L << Math.min(attempts, 8))
                                + ThreadLocalRandom.current().nextLong(501);
                finish(
                        e,
                        w,
                        intent,
                        permanent || attempts >= 12 ? "ISOLATED" : "PENDING",
                        code,
                        delay);
                failed++;
            }
        }
        return new SerialRecoveryService.Report(done, failed);
    }

    private Map<String, Object> claim(String e, String w) {
        try (var session = sessions.openSession(false)) {
            SerialRecoveryService.requireWritable(session, e, w);
            var mapper = session.getMapper(SerialReleaseMapper.class);
            var now = Timestamp.from(clock.instant());
            var row = mapper.next(e, w, now);
            if (row == null) return null;
            if (number(row, "attempts") >= 12) {
                mapper.finish(
                        e,
                        w,
                        (String) row.get("id"),
                        number(row, "claim_epoch"),
                        "ISOLATED",
                        "ATTEMPTS_EXHAUSTED",
                        now,
                        now);
                session.commit();
                return null;
            }
            if (mapper.claim(
                            e,
                            w,
                            (String) row.get("id"),
                            number(row, "claim_epoch"),
                            Timestamp.from(clock.instant().plusSeconds(15)),
                            now)
                    != 1) throw new InventoryException("VERSION_CONFLICT", "源仓释放领取竞争");
            row.put("claim_epoch", number(row, "claim_epoch") + 1);
            row.put("attempts", number(row, "attempts") + 1);
            session.commit();
            return row;
        }
    }

    private boolean finish(
            String e, String w, Map<String, Object> row, String state, String error, long delay) {
        try (var session = sessions.openSession(false)) {
            SerialRecoveryService.requireWritable(session, e, w);
            int changed =
                    session.getMapper(SerialReleaseMapper.class)
                            .finish(
                                    e,
                                    w,
                                    (String) row.get("id"),
                                    number(row, "claim_epoch"),
                                    state,
                                    error,
                                    Timestamp.from(clock.instant().plusMillis(delay)),
                                    Timestamp.from(clock.instant()));
            session.commit();
            return changed == 1;
        }
    }

    private static long number(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }
}
