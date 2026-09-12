package com.lrj.wms.inventory.serial;

import com.lrj.wms.inventory.inventory.InventoryApplicationService;
import com.lrj.wms.inventory.count.CountService;
import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.Quantity;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;

/**
 * 仓内序列号 HOLD 收货。登记激活后本地放行，质量仍保持 HOLD。
 * 登记不可用时保留意向和异常状态。
 */
public final class SerialReceiptService {
    public static final String STATE_INTENDED = "INTENDED";
    public static final String STATE_HOLD_RECEIVED = "HOLD_RECEIVED";
    public static final String STATE_AUTHORIZED = "AUTHORIZED";
    public static final String STATE_EXCEPTION = "EXCEPTION";
    public static final String REGISTRY_NONE = "NONE";
    public static final String REGISTRY_UNAVAILABLE = "REGISTRY_UNAVAILABLE";

    private final SqlSession session;
    private final Clock clock;
    private final SerialRegistryPort registry;

    public SerialReceiptService(SqlSession session, Clock clock, SerialRegistryPort registry) {
        this.session = session;
        this.clock = clock;
        this.registry = registry;
    }

    /** HOLD 收货并协调登记。同操作重放不二次加量；登记失败保留本地记录。 */
    public Map<String, Object> receiveHold(String enterpriseId, String warehouseId, String operationId, String documentId,
            String actorId, String serial, StockBucketKey bucket) {
        String normalized = normalize(serial);
        if (!InventoryCodes.QUALITY_HOLD.equals(bucket.qualityCode())) {
            throw new InventoryException("INVALID_QUALITY", "序列号收货必须进入 HOLD 桶");
        }
        if (!enterpriseId.equals(bucket.enterpriseId()) || !warehouseId.equals(bucket.warehouseId())) {
            throw new InventoryException("SCOPE_MISMATCH", "序列号收货范围与桶不一致");
        }
        Timestamp now = Timestamp.from(clock.instant());
        LocalSerialMapper locals = session.getMapper(LocalSerialMapper.class);
        locals.insertIgnore(UUID.randomUUID().toString(), enterpriseId, warehouseId, normalized, bucket.skuId(),
                bucket.lotId(), null, STATE_INTENDED, operationId, REGISTRY_NONE, null, now);
        Map<String, Object> row = locals.lock(enterpriseId, warehouseId, normalized);
        if (row == null) {
            throw new InventoryException("VERSION_CONFLICT", "本地序列号意向竞争");
        }
        if (!operationId.equals(String.valueOf(row.get("receipt_operation_id")))) {
            throw new InventoryException("SERIAL_ALREADY_RECEIVED", "序列号已被其他收货操作占用");
        }
        if (SerialTransferLocalService.STATE_SEALED.equals(String.valueOf(row.get("state")))) {
            throw new InventoryException("SERIAL_SEALED", "源仓已封闭，不能再按收货放行");
        }
        if (STATE_AUTHORIZED.equals(String.valueOf(row.get("state")))) {
            return view(row);
        }
        if (STATE_INTENDED.equals(String.valueOf(row.get("state")))) {
            new InventoryApplicationService(session, clock).receive(enterpriseId, warehouseId, operationId, documentId,
                    actorId, bucket, Quantity.parse("1", 0));
            Map<String, Object> balance = session.getMapper(InventoryMapper.class).lockBalanceByDimension(enterpriseId,
                    warehouseId, bucket.ownerId(), bucket.locationId(), bucket.skuId(), bucket.lotId(),
                    bucket.qualityCode());
            if (balance == null) {
                throw new InventoryException("VERSION_CONFLICT", "HOLD 收货后找不到余额");
            }
            locals.updateState(enterpriseId, warehouseId, normalized, String.valueOf(balance.get("id")),
                    STATE_HOLD_RECEIVED, REGISTRY_NONE, null, 0L, now);
            row = locals.lock(enterpriseId, warehouseId, normalized);
        }
        return syncRegistry(enterpriseId, warehouseId, operationId, normalized, bucket.skuId(), row);
    }

    /** 登记恢复后补激活。查询本地记录，不 invent 新库存。 */
    public Map<String, Object> recover(String enterpriseId, String warehouseId, String serial) {
        String normalized = normalize(serial);
        Map<String, Object> row = session.getMapper(LocalSerialMapper.class).lock(enterpriseId, warehouseId, normalized);
        if (row == null) {
            throw new InventoryException("SERIAL_NOT_FOUND", "本地没有该序列号意向");
        }
        if (SerialTransferLocalService.STATE_SEALED.equals(String.valueOf(row.get("state")))
                || STATE_AUTHORIZED.equals(String.valueOf(row.get("state")))) {
            return view(row);
        }
        if (CountService.MISSING.equals(String.valueOf(row.get("state")))
                || CountService.MISSING_PENDING.equals(String.valueOf(row.get("state")))) {
            throw new InventoryException("SERIAL_MISSING", "失踪序列号不能按原收货恢复");
        }
        return syncRegistry(enterpriseId, warehouseId, String.valueOf(row.get("receipt_operation_id")), normalized,
                String.valueOf(row.get("sku_id")), row);
    }

    /** 本地恢复查询，不访问登记服务。 */
    public Map<String, Object> get(String enterpriseId, String warehouseId, String serial) {
        Map<String, Object> row = session.getMapper(LocalSerialMapper.class).get(enterpriseId, warehouseId,
                normalize(serial));
        if (row == null) {
            throw new InventoryException("SERIAL_NOT_FOUND", "本地没有该序列号意向");
        }
        return view(row);
    }

    public static String normalize(String serial) {
        if (serial == null || serial.isBlank()) {
            throw new InventoryException("INVALID_SERIAL", "序列号不能为空");
        }
        return serial.trim().toUpperCase();
    }

    private Map<String, Object> syncRegistry(String enterpriseId, String warehouseId, String operationId, String serial,
            String skuId, Map<String, Object> current) {
        LocalSerialMapper locals = session.getMapper(LocalSerialMapper.class);
        Timestamp now = Timestamp.from(clock.instant());
        String balanceId = current.get("balance_id") == null ? null : String.valueOf(current.get("balance_id"));
        try {
            Map<String, Object> claimed = registry.claim(enterpriseId, skuId, serial, warehouseId, operationId);
            try {
                Map<String, Object> active = registry.activate(enterpriseId, skuId, serial, warehouseId, operationId);
                long epoch = longValue(active.get("ownerEpoch"), claimed.get("ownerEpoch"));
                locals.updateState(enterpriseId, warehouseId, serial, balanceId, STATE_AUTHORIZED,
                        String.valueOf(active.get("state")), null, epoch, now);
            } catch (SerialRegistryUnavailableException error) {
                locals.updateState(enterpriseId, warehouseId, serial, balanceId, STATE_HOLD_RECEIVED,
                        String.valueOf(claimed.get("state")), REGISTRY_UNAVAILABLE,
                        longValue(claimed.get("ownerEpoch")), now);
            } catch (SerialRegistryConflictException error) {
                locals.updateState(enterpriseId, warehouseId, serial, balanceId, STATE_EXCEPTION,
                        String.valueOf(claimed.get("state")), error.code(), longValue(claimed.get("ownerEpoch")), now);
            }
        } catch (SerialRegistryUnavailableException error) {
            locals.updateState(enterpriseId, warehouseId, serial, balanceId, STATE_EXCEPTION, REGISTRY_NONE,
                    REGISTRY_UNAVAILABLE, 0L, now);
        } catch (SerialRegistryConflictException error) {
            locals.updateState(enterpriseId, warehouseId, serial, balanceId, STATE_EXCEPTION, REGISTRY_NONE, error.code(),
                    0L, now);
        }
        return view(locals.lock(enterpriseId, warehouseId, serial));
    }

    private static long longValue(Object... candidates) {
        for (Object candidate : candidates) {
            if (candidate instanceof Number number) {
                return number.longValue();
            }
        }
        return 0L;
    }

    private static Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serialId", row.get("serial_id"));
        body.put("state", row.get("state"));
        body.put("balanceId", row.get("balance_id"));
        body.put("receiptOperationId", row.get("receipt_operation_id"));
        body.put("registryState", row.get("registry_state"));
        body.put("registryError", row.get("registry_error"));
        body.put("ownerEpoch", row.get("owner_epoch"));
        body.put("transferId", row.get("transfer_id"));
        body.put("sourceReleaseRef", row.get("source_release_ref"));
        return body;
    }
}
