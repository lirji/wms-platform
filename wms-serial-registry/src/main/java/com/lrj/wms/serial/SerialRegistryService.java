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
    public static final String STATE_TRANSFER_PREPARED = "TRANSFER_PREPARED";
    public static final String STATE_IN_TRANSIT = "IN_TRANSIT";
    public static final String STATE_RECEIVING = "RECEIVING";
    public static final String STATE_MISSING = "MISSING";
    public static final String STATE_FOUND_CLAIMED = "FOUND_CLAIMED";
    public static final String TRANSFER_PREPARED = "PREPARED";
    public static final String TRANSFER_IN_TRANSIT = "IN_TRANSIT";
    public static final String TRANSFER_RECEIVING = "RECEIVING";
    public static final String TRANSFER_COMPLETED = "COMPLETED";
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
        if (STATE_MISSING.equals(String.valueOf(row.get("state")))) {
            throw new SerialRegistryException("SERIAL_MISSING", "失踪序列号不能按首次认领占用");
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
        if (STATE_MISSING.equals(String.valueOf(row.get("state")))) {
            throw new SerialRegistryException("SERIAL_MISSING", "失踪序列号不能按原认领激活");
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

    /**
     * 源仓准备转移。校验归属代际后进入 TRANSFER_PREPARED，固定 transfer 与目的仓。
     * 同操作重放；旧 epoch 或进行中的其他转移拒绝。
     */
    public Map<String, Object> prepareTransfer(String enterpriseId, String skuId, String serial, String sourceWarehouseId,
            String targetWarehouseId, String transferId, long expectedEpoch, String operationId) {
        requireId(transferId, "INVALID_TRANSFER", "转移标识不能为空");
        requireId(operationId, "INVALID_OPERATION", "准备操作不能为空");
        requireId(sourceWarehouseId, "INVALID_WAREHOUSE", "源仓不能为空");
        requireId(targetWarehouseId, "INVALID_WAREHOUSE", "目的仓不能为空");
        if (sourceWarehouseId.equals(targetWarehouseId)) {
            throw new SerialRegistryException("INVALID_WAREHOUSE", "源仓与目的仓不能相同");
        }
        String normalized = normalize(serial);
        Timestamp now = Timestamp.from(clock.instant());
        SerialRegistryMapper identities = session.getMapper(SerialRegistryMapper.class);
        SerialTransferMapper transfers = session.getMapper(SerialTransferMapper.class);
        Map<String, Object> row = identities.lockIdentity(enterpriseId, skuId, normalized);
        if (row == null) {
            throw new SerialRegistryException("SERIAL_NOT_FOUND", "序列号尚未登记");
        }
        if (sameTransfer(row, transferId) && inFlight(String.valueOf(row.get("state")))) {
            return replayPrepared(row, lockExistingTransfer(transfers, enterpriseId, skuId, normalized, transferId),
                    operationId, sourceWarehouseId, targetWarehouseId);
        }
        if (expectedEpoch != asLong(row.get("owner_epoch"))) {
            throw staleEpoch(row, expectedEpoch);
        }
        if (!STATE_ACTIVE.equals(String.valueOf(row.get("state")))) {
            throw new SerialRegistryException("SERIAL_STATE_CONFLICT", "当前登记状态不能准备转移");
        }
        if (!sourceWarehouseId.equals(String.valueOf(row.get("owner_warehouse_id")))) {
            throw new SerialRegistryException("SERIAL_OWNER_MISMATCH", "准备仓与登记归属不一致");
        }
        transfers.insertIgnore(UUID.randomUUID().toString(), enterpriseId, skuId, normalized, String.valueOf(row.get("id")),
                transferId, sourceWarehouseId, targetWarehouseId, expectedEpoch, TRANSFER_PREPARED, operationId, now);
        Map<String, Object> transfer = transfers.lock(enterpriseId, skuId, normalized, transferId);
        if (transfer == null) {
            throw new SerialRegistryException("VERSION_CONFLICT", "转移准备竞争");
        }
        if (!operationId.equals(String.valueOf(transfer.get("prepare_operation_id")))) {
            throw new SerialRegistryException("SERIAL_OPERATION_MISMATCH", "准备操作与已记录不一致");
        }
        if (!sourceWarehouseId.equals(String.valueOf(transfer.get("source_warehouse_id")))
                || !targetWarehouseId.equals(String.valueOf(transfer.get("target_warehouse_id")))
                || expectedEpoch != asLong(transfer.get("from_epoch"))) {
            throw new SerialRegistryException("SERIAL_OWNER_MISMATCH", "转移仓或代际与已记录不一致");
        }
        if (identities.casTransferState(enterpriseId, skuId, normalized, STATE_ACTIVE, STATE_TRANSFER_PREPARED,
                transferId, expectedEpoch, now) != 1) {
            throw new SerialRegistryException("VERSION_CONFLICT", "转移准备竞争");
        }
        return view(identities.lockIdentity(enterpriseId, skuId, normalized));
    }

    /**
     * 观察到源仓已持久化释放后进入 IN_TRANSIT。登记未见到释放事实前不能授予目的仓。
     * 同释放引用重放；旧 epoch 覆盖拒绝。
     */
    public Map<String, Object> observeSourceRelease(String enterpriseId, String skuId, String serial, String transferId,
            String sourceReleaseRef, long expectedEpoch) {
        requireId(transferId, "INVALID_TRANSFER", "转移标识不能为空");
        requireId(sourceReleaseRef, "INVALID_RELEASE", "源仓释放引用不能为空");
        String normalized = normalize(serial);
        Timestamp now = Timestamp.from(clock.instant());
        SerialRegistryMapper identities = session.getMapper(SerialRegistryMapper.class);
        SerialTransferMapper transfers = session.getMapper(SerialTransferMapper.class);
        Map<String, Object> row = requireIdentity(identities, enterpriseId, skuId, normalized);
        Map<String, Object> transfer = lockExistingTransfer(transfers, enterpriseId, skuId, normalized, transferId);
        if (sameRef(transfer.get("source_release_ref"), sourceReleaseRef) && sameTransfer(row, transferId)) {
            return view(row);
        }
        if (expectedEpoch != asLong(row.get("owner_epoch")) || expectedEpoch != asLong(transfer.get("from_epoch"))) {
            throw staleEpoch(row, expectedEpoch);
        }
        if (transfer.get("source_release_ref") != null) {
            throw new SerialRegistryException("SERIAL_OPERATION_MISMATCH", "源仓释放引用与已记录不一致");
        }
        if (!STATE_TRANSFER_PREPARED.equals(String.valueOf(row.get("state"))) || !sameTransfer(row, transferId)) {
            throw new SerialRegistryException("SERIAL_STATE_CONFLICT", "当前登记状态不能确认源仓释放");
        }
        if (transfers.casRelease(enterpriseId, skuId, normalized, transferId, TRANSFER_PREPARED, TRANSFER_IN_TRANSIT,
                sourceReleaseRef, now) != 1
                || identities.casTransferState(enterpriseId, skuId, normalized, STATE_TRANSFER_PREPARED,
                        STATE_IN_TRANSIT, transferId, expectedEpoch, now) != 1) {
            throw new SerialRegistryException("VERSION_CONFLICT", "源仓释放确认竞争");
        }
        return view(identities.lockIdentity(enterpriseId, skuId, normalized));
    }

    /**
     * 目的仓开始接收。必须已 IN_TRANSIT。同接收引用重放；旧事件拒绝。
     * 未见到源仓释放前不得进入 RECEIVING。
     */
    public Map<String, Object> startReceiving(String enterpriseId, String skuId, String serial, String transferId,
            String targetWarehouseId, String targetReceiptRef, long expectedFromEpoch) {
        requireId(transferId, "INVALID_TRANSFER", "转移标识不能为空");
        requireId(targetReceiptRef, "INVALID_RECEIPT", "目的接收引用不能为空");
        requireId(targetWarehouseId, "INVALID_WAREHOUSE", "目的仓不能为空");
        String normalized = normalize(serial);
        Timestamp now = Timestamp.from(clock.instant());
        SerialRegistryMapper identities = session.getMapper(SerialRegistryMapper.class);
        SerialTransferMapper transfers = session.getMapper(SerialTransferMapper.class);
        Map<String, Object> row = requireIdentity(identities, enterpriseId, skuId, normalized);
        Map<String, Object> transfer = lockExistingTransfer(transfers, enterpriseId, skuId, normalized, transferId);
        if (!targetWarehouseId.equals(String.valueOf(transfer.get("target_warehouse_id")))) {
            throw new SerialRegistryException("SERIAL_OWNER_MISMATCH", "接收仓与转移目的仓不一致");
        }
        if (sameRef(transfer.get("target_receipt_ref"), targetReceiptRef) && sameTransfer(row, transferId)
                && (STATE_RECEIVING.equals(String.valueOf(row.get("state")))
                        || STATE_ACTIVE.equals(String.valueOf(row.get("state"))))) {
            return view(row);
        }
        if (expectedFromEpoch != asLong(row.get("owner_epoch"))
                || expectedFromEpoch != asLong(transfer.get("from_epoch"))) {
            throw staleEpoch(row, expectedFromEpoch);
        }
        if (transfer.get("target_receipt_ref") != null) {
            throw new SerialRegistryException("SERIAL_OPERATION_MISMATCH", "目的接收引用与已记录不一致");
        }
        if (!STATE_IN_TRANSIT.equals(String.valueOf(row.get("state"))) || !sameTransfer(row, transferId)) {
            throw new SerialRegistryException("SERIAL_STATE_CONFLICT", "登记未在途，不能授予目的仓接收");
        }
        if (transfers.casReceiving(enterpriseId, skuId, normalized, transferId, TRANSFER_IN_TRANSIT, TRANSFER_RECEIVING,
                targetReceiptRef, now) != 1
                || identities.casTransferState(enterpriseId, skuId, normalized, STATE_IN_TRANSIT, STATE_RECEIVING,
                        transferId, expectedFromEpoch, now) != 1) {
            throw new SerialRegistryException("VERSION_CONFLICT", "目的接收竞争");
        }
        return view(identities.lockIdentity(enterpriseId, skuId, normalized));
    }

    /** 核实目的库存凭证后切换 ACTIVE 并递增 owner_epoch。同接收引用重放，不二次改归属。 */
    public Map<String, Object> confirmDestination(String enterpriseId, String skuId, String serial, String transferId,
            String targetWarehouseId, String targetReceiptRef) {
        requireId(transferId, "INVALID_TRANSFER", "转移标识不能为空");
        requireId(targetReceiptRef, "INVALID_RECEIPT", "目的接收引用不能为空");
        requireId(targetWarehouseId, "INVALID_WAREHOUSE", "目的仓不能为空");
        String normalized = normalize(serial);
        Timestamp now = Timestamp.from(clock.instant());
        SerialRegistryMapper identities = session.getMapper(SerialRegistryMapper.class);
        SerialTransferMapper transfers = session.getMapper(SerialTransferMapper.class);
        Map<String, Object> row = requireIdentity(identities, enterpriseId, skuId, normalized);
        Map<String, Object> transfer = lockExistingTransfer(transfers, enterpriseId, skuId, normalized, transferId);
        if (STATE_ACTIVE.equals(String.valueOf(row.get("state"))) && sameTransfer(row, transferId)
                && targetWarehouseId.equals(String.valueOf(row.get("owner_warehouse_id")))
                && sameRef(row.get("receipt_operation_id"), targetReceiptRef)) {
            return view(row);
        }
        if (!targetWarehouseId.equals(String.valueOf(transfer.get("target_warehouse_id")))) {
            throw new SerialRegistryException("SERIAL_OWNER_MISMATCH", "确认仓与转移目的仓不一致");
        }
        if (transfer.get("target_receipt_ref") != null && !sameRef(transfer.get("target_receipt_ref"), targetReceiptRef)) {
            throw new SerialRegistryException("SERIAL_OPERATION_MISMATCH", "目的接收引用与已记录不一致");
        }
        if (!STATE_RECEIVING.equals(String.valueOf(row.get("state"))) || !sameTransfer(row, transferId)) {
            if (asLong(row.get("owner_epoch")) > asLong(transfer.get("from_epoch"))) {
                throw staleEpoch(row, asLong(transfer.get("from_epoch")));
            }
            throw new SerialRegistryException("SERIAL_STATE_CONFLICT", "当前登记状态不能确认目的归属");
        }
        if (!sameRef(transfer.get("target_receipt_ref"), targetReceiptRef)) {
            throw new SerialRegistryException("SERIAL_OPERATION_MISMATCH", "目的接收引用与已记录不一致");
        }
        long toEpoch = asLong(transfer.get("from_epoch")) + 1;
        if (transfers.casComplete(enterpriseId, skuId, normalized, transferId, targetReceiptRef, toEpoch, now) != 1
                || identities.casConfirmDestination(enterpriseId, skuId, normalized, targetWarehouseId, transferId,
                        targetReceiptRef, toEpoch, now) != 1) {
            throw new SerialRegistryException("VERSION_CONFLICT", "目的确认竞争");
        }
        return view(identities.lockIdentity(enterpriseId, skuId, normalized));
    }

    /** 盘亏事实到达后标 MISSING，禁止再授权。同事实重放。 */
    public Map<String, Object> markMissing(String enterpriseId, String skuId, String serial, String warehouseId,
            String factRef, long expectedEpoch) {
        requireId(factRef, "INVALID_RELEASE", "失踪事实不能为空");
        requireId(warehouseId, "INVALID_WAREHOUSE", "仓不能为空");
        String normalized = normalize(serial);
        Timestamp now = Timestamp.from(clock.instant());
        SerialRegistryMapper identities = session.getMapper(SerialRegistryMapper.class);
        Map<String, Object> row = requireIdentity(identities, enterpriseId, skuId, normalized);
        if (STATE_MISSING.equals(String.valueOf(row.get("state")))
                && sameRef(row.get("receipt_operation_id"), factRef)) {
            return view(row);
        }
        if (expectedEpoch != asLong(row.get("owner_epoch"))) {
            throw staleEpoch(row, expectedEpoch);
        }
        if (!STATE_ACTIVE.equals(String.valueOf(row.get("state")))
                || !warehouseId.equals(String.valueOf(row.get("owner_warehouse_id")))) {
            throw new SerialRegistryException("SERIAL_STATE_CONFLICT", "当前登记状态不能标失踪");
        }
        if (identities.casMissing(enterpriseId, skuId, normalized, warehouseId, factRef, expectedEpoch, now) != 1) {
            throw new SerialRegistryException("VERSION_CONFLICT", "失踪登记竞争");
        }
        return view(identities.lockIdentity(enterpriseId, skuId, normalized));
    }

    /** 盘盈：失踪身份走 FOUND_CLAIMED；全新身份走 CLAIMED。 */
    public Map<String, Object> claimFound(String enterpriseId, String skuId, String serial, String warehouseId,
            String operationId) {
        requireId(operationId, "INVALID_OPERATION", "盘盈操作不能为空");
        requireId(warehouseId, "INVALID_WAREHOUSE", "仓不能为空");
        String normalized = normalize(serial);
        Timestamp now = Timestamp.from(clock.instant());
        SerialRegistryMapper identities = session.getMapper(SerialRegistryMapper.class);
        Map<String, Object> existing = identities.getIdentity(enterpriseId, skuId, normalized);
        if (existing == null) {
            return claim(enterpriseId, skuId, serial, warehouseId, operationId);
        }
        Map<String, Object> row = identities.lockIdentity(enterpriseId, skuId, normalized);
        if (STATE_FOUND_CLAIMED.equals(String.valueOf(row.get("state")))
                && operationId.equals(String.valueOf(row.get("claim_operation_id")))) {
            return view(row);
        }
        if (STATE_ACTIVE.equals(String.valueOf(row.get("state")))) {
            throw new SerialRegistryException("SERIAL_ALREADY_CLAIMED", "序列号仍是有效授权，不能盘盈认领");
        }
        if (!STATE_MISSING.equals(String.valueOf(row.get("state")))) {
            throw new SerialRegistryException("SERIAL_STATE_CONFLICT", "当前登记状态不能盘盈认领");
        }
        if (identities.casFound(enterpriseId, skuId, normalized, warehouseId, operationId, now) != 1) {
            throw new SerialRegistryException("VERSION_CONFLICT", "盘盈认领竞争");
        }
        return view(identities.lockIdentity(enterpriseId, skuId, normalized));
    }

    /** FOUND_CLAIMED 核实后激活。同操作重放。 */
    public Map<String, Object> activateFound(String enterpriseId, String skuId, String serial, String warehouseId,
            String operationId) {
        String normalized = normalize(serial);
        Timestamp now = Timestamp.from(clock.instant());
        SerialRegistryMapper identities = session.getMapper(SerialRegistryMapper.class);
        Map<String, Object> row = requireIdentity(identities, enterpriseId, skuId, normalized);
        if (STATE_ACTIVE.equals(String.valueOf(row.get("state")))
                && warehouseId.equals(String.valueOf(row.get("owner_warehouse_id")))) {
            return view(row);
        }
        if (!STATE_FOUND_CLAIMED.equals(String.valueOf(row.get("state")))) {
            return activate(enterpriseId, skuId, serial, warehouseId, operationId);
        }
        if (!warehouseId.equals(String.valueOf(row.get("owner_warehouse_id")))) {
            throw new SerialRegistryException("SERIAL_OWNER_MISMATCH", "盘盈仓与登记归属不一致");
        }
        if (!operationId.equals(String.valueOf(row.get("claim_operation_id")))) {
            throw new SerialRegistryException("SERIAL_OPERATION_MISMATCH", "盘盈激活操作与认领不一致");
        }
        if (identities.activateFound(enterpriseId, skuId, normalized, operationId, now) != 1) {
            throw new SerialRegistryException("VERSION_CONFLICT", "盘盈激活竞争");
        }
        return view(identities.lockIdentity(enterpriseId, skuId, normalized));
    }

    /** 转移审计恢复查询，不加锁。 */
    public Map<String, Object> getTransfer(String enterpriseId, String skuId, String serial, String transferId) {
        Map<String, Object> transfer = session.getMapper(SerialTransferMapper.class).get(enterpriseId, skuId,
                normalize(serial), transferId);
        if (transfer == null) {
            throw new SerialRegistryException("SERIAL_TRANSFER_NOT_FOUND", "没有该序列号转移");
        }
        return transferView(transfer);
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
        body.put("transferId", row.get("transfer_id"));
        body.put("receiptOperationId", row.get("receipt_operation_id"));
        body.put("routeBucket", row.get("route_bucket"));
        return body;
    }

    private static Map<String, Object> transferView(Map<String, Object> row) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", row.get("id"));
        body.put("transferId", row.get("transfer_id"));
        body.put("state", row.get("state"));
        body.put("sourceWarehouseId", row.get("source_warehouse_id"));
        body.put("targetWarehouseId", row.get("target_warehouse_id"));
        body.put("fromEpoch", row.get("from_epoch"));
        body.put("toEpoch", row.get("to_epoch"));
        body.put("sourceReleaseRef", row.get("source_release_ref"));
        body.put("targetReceiptRef", row.get("target_receipt_ref"));
        return body;
    }

    private Map<String, Object> replayPrepared(Map<String, Object> row, Map<String, Object> transfer, String operationId,
            String sourceWarehouseId, String targetWarehouseId) {
        if (!operationId.equals(String.valueOf(transfer.get("prepare_operation_id")))) {
            throw new SerialRegistryException("SERIAL_OPERATION_MISMATCH", "准备操作与已记录不一致");
        }
        if (!sourceWarehouseId.equals(String.valueOf(transfer.get("source_warehouse_id")))
                || !targetWarehouseId.equals(String.valueOf(transfer.get("target_warehouse_id")))) {
            throw new SerialRegistryException("SERIAL_OWNER_MISMATCH", "转移仓与已记录不一致");
        }
        return view(row);
    }

    private static Map<String, Object> requireIdentity(SerialRegistryMapper identities, String enterpriseId,
            String skuId, String serial) {
        Map<String, Object> row = identities.lockIdentity(enterpriseId, skuId, serial);
        if (row == null) {
            throw new SerialRegistryException("SERIAL_NOT_FOUND", "序列号尚未登记");
        }
        return row;
    }

    private Map<String, Object> lockExistingTransfer(SerialTransferMapper transfers, String enterpriseId, String skuId,
            String serial, String transferId) {
        Map<String, Object> transfer = transfers.lock(enterpriseId, skuId, serial, transferId);
        if (transfer == null) {
            throw new SerialRegistryException("SERIAL_TRANSFER_NOT_FOUND", "没有该序列号转移");
        }
        return transfer;
    }

    private static SerialRegistryException staleEpoch(Map<String, Object> row, long expectedEpoch) {
        if (expectedEpoch < asLong(row.get("owner_epoch"))) {
            return new SerialRegistryException("STALE_EPOCH", "旧归属代际事件不能覆盖当前授权");
        }
        return new SerialRegistryException("STALE_EPOCH", "归属代际与当前授权不一致");
    }

    private static boolean sameTransfer(Map<String, Object> row, String transferId) {
        return transferId.equals(String.valueOf(row.get("transfer_id")));
    }

    private static boolean sameRef(Object stored, String expected) {
        return stored != null && expected.equals(String.valueOf(stored));
    }

    private static boolean inFlight(String state) {
        return STATE_TRANSFER_PREPARED.equals(state) || STATE_IN_TRANSIT.equals(state)
                || STATE_RECEIVING.equals(state);
    }

    private static void requireId(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw new SerialRegistryException(code, message);
        }
    }

    static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        return Long.parseLong(String.valueOf(value));
    }
}
