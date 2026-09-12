package com.lrj.wms.serial;

import com.lrj.wms.runtime.command.CommandKeys;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.apache.ibatis.session.SqlSessionFactory;

/** 内部命令的本地事务边界；跨动作共享幂等审计，领域服务只执行登记规则。 */
public final class SerialCommandService {
    private final SqlSessionFactory sessions;
    private final Clock clock;
    public SerialCommandService(SqlSessionFactory sessions, Clock clock) { this.sessions = sessions; this.clock = clock; }

    /** HTTP命令键与原收货operation引用含义不同，两者都固定，防止网络重试变成新认领。 */
    public Map<String, Object> execute(String enterprise, String warehouse, String command, String actor,
            String action, Object request, Function<SerialRegistryService, Map<String, Object>> operation) {
        CommandKeys.resolve(command, null);
        String hash = RuntimeMessage.hash(RuntimeMessage.JSON.writeValueAsString(List.of(enterprise, warehouse, actor, action, request)));
        try (var session = sessions.openSession(false)) {
            var mapper = session.getMapper(SerialHttpCommandMapper.class);
            mapper.insert(UUID.randomUUID().toString(), enterprise, warehouse, command, hash, action, actor, Timestamp.from(clock.instant()));
            var stored = mapper.lock(enterprise, command);
            if (stored == null || !hash.equals(stored.get("request_hash"))) {
                throw new SerialRegistryException("IDEMPOTENCY_PAYLOAD_MISMATCH", "同命令键的动作或参数不同");
            }
            if (stored.get("result") != null) {
                // 登记响应涉及授权，旧ACTIVE审计不能覆盖后续MISSING或转移；领域动作本身必须可幂等重放。
                var current = operation.apply(new SerialRegistryService(session, clock));
                session.commit();
                return current;
            }
            var result = operation.apply(new SerialRegistryService(session, clock));
            if (mapper.finish(enterprise, command, RuntimeMessage.JSON.writeValueAsString(result)) != 1) {
                throw new SerialRegistryException("VERSION_CONFLICT", "登记命令结果写入竞争");
            }
            session.commit();
            return result;
        }
    }
}
