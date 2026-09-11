package com.lrj.wms.serial;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;

/** 按企业+SKU+规范化序列号认领唯一身份。仓只是归属，不是唯一维。 */
public final class SerialRegistryService {
    public static final String STATE_CLAIMED = "CLAIMED";
    public static final String STATE_ACTIVE = "ACTIVE";
    public static final int BUCKETS = 64;

    private final SqlSession session;
    private final Clock clock;

    public SerialRegistryService(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    /** 首次认领写入 CLAIMED；同操作重放；他仓/他操作冲突拒绝。 */
    public Map<String, Object> claim(String enterpriseId, String skuId, String serial, String warehouseId,
            String operationId) {
        String normalized = normalize(serial);
        int bucket = routeBucket(enterpriseId, skuId, normalized);
        Timestamp now = Timestamp.from(clock.instant());
        SerialRegistryMapper mapper = session.getMapper(SerialRegistryMapper.class);
        mapper.insertIgnore(UUID.randomUUID().toString(), enterpriseId, skuId, normalized, STATE_CLAIMED, warehouseId,
                operationId, bucket, now);
        Map<String, Object> row = mapper.lockIdentity(enterpriseId, skuId, normalized);
        if (row == null) {
            throw new SerialRegistryException("VERSION_CONFLICT", "登记认领竞争");
        }
        if (!operationId.equals(String.valueOf(row.get("claim_operation_id")))) {
            throw new SerialRegistryException("SERIAL_ALREADY_CLAIMED", "序列号已被其他操作认领");
        }
        return view(row);
    }

    /** CLAIMED 核实收货后激活。同仓重放保持 ACTIVE，他仓或非认领态拒绝。 */
    public Map<String, Object> activate(String enterpriseId, String skuId, String serial, String warehouseId,
            String operationId) {
        String normalized = normalize(serial);
        Timestamp now = Timestamp.from(clock.instant());
        SerialRegistryMapper mapper = session.getMapper(SerialRegistryMapper.class);
        Map<String, Object> row = mapper.lockIdentity(enterpriseId, skuId, normalized);
        if (row == null) {
            throw new SerialRegistryException("SERIAL_NOT_FOUND", "序列号尚未认领");
        }
        if (!warehouseId.equals(String.valueOf(row.get("owner_warehouse_id")))) {
            throw new SerialRegistryException("SERIAL_OWNER_MISMATCH", "激活仓与登记归属不一致");
        }
        String state = String.valueOf(row.get("state"));
        if (STATE_ACTIVE.equals(state)) {
            return view(row);
        }
        if (!STATE_CLAIMED.equals(state)) {
            throw new SerialRegistryException("SERIAL_STATE_CONFLICT", "当前登记状态不能激活");
        }
        if (!operationId.equals(String.valueOf(row.get("claim_operation_id")))) {
            throw new SerialRegistryException("SERIAL_OPERATION_MISMATCH", "激活操作与认领不一致");
        }
        if (mapper.activateClaimed(enterpriseId, skuId, normalized, STATE_ACTIVE, now) != 1) {
            throw new SerialRegistryException("VERSION_CONFLICT", "登记激活竞争");
        }
        return view(mapper.lockIdentity(enterpriseId, skuId, normalized));
    }

    /** 恢复查询，不加锁。不存在则业务码拒绝。 */
    public Map<String, Object> get(String enterpriseId, String skuId, String serial) {
        Map<String, Object> row = session.getMapper(SerialRegistryMapper.class)
                .getIdentity(enterpriseId, skuId, normalize(serial));
        if (row == null) {
            throw new SerialRegistryException("SERIAL_NOT_FOUND", "序列号尚未登记");
        }
        return view(row);
    }

    public static String normalize(String serial) {
        if (serial == null || serial.isBlank()) {
            throw new SerialRegistryException("INVALID_SERIAL", "序列号不能为空");
        }
        return serial.trim().toUpperCase();
    }

    public static int routeBucket(String enterpriseId, String skuId, String normalized) {
        int hash = (enterpriseId + '\u001f' + skuId + '\u001f' + normalized).hashCode();
        return Math.floorMod(hash, BUCKETS);
    }

    private static Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", row.get("id"));
        body.put("state", row.get("state"));
        body.put("normalizedSerial", row.get("normalized_serial"));
        body.put("ownerWarehouseId", row.get("owner_warehouse_id"));
        body.put("ownerEpoch", row.get("owner_epoch"));
        body.put("claimOperationId", row.get("claim_operation_id"));
        body.put("routeBucket", row.get("route_bucket"));
        return body;
    }
}
