package com.lrj.wms.inventory.serial;

import com.lrj.wms.inventory.inventory.InventoryApplicationService;
import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.Quantity;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;

/**
 * 仓内序列号转移。源仓 SEALED 后旧授权消息不得恢复可用；目的仓未获登记接收前保持 HOLD。
 * 源仓封闭时扣 1 个 on_hand；同释放引用重放不二次扣减。不发明 OQ-03。
 */
public final class SerialTransferLocalService {
    public static final String STATE_SEALED = "SEALED";
    public static final String STATE_RECEIVING = "RECEIVING";

    private final SqlSession session;
    private final Clock clock;
    private final SerialTransferRegistryPort registry;

    public SerialTransferLocalService(SqlSession session, Clock clock, SerialTransferRegistryPort registry) {
        this.session = session;
        this.clock = clock;
        this.registry = registry;
    }

    /** 源仓封闭本地授权。同转移同释放引用重放；旧 epoch 拒绝。 */
    public Map<String, Object> sealSource(String enterpriseId, String warehouseId, String serial, String transferId,
            long expectedEpoch, String releaseRef) {
        SerialRecoveryService.requireWritable(session,enterpriseId,warehouseId);
        if(expectedEpoch<0) throw new InventoryException("STALE_EPOCH","归属代际不能为负");
        requireId(transferId, "INVALID_TRANSFER", "转移标识不能为空");
        requireId(releaseRef, "INVALID_RELEASE", "源仓释放引用不能为空");
        String normalized = SerialReceiptService.normalize(serial);
        Timestamp now = Timestamp.from(clock.instant());
        LocalSerialMapper locals = session.getMapper(LocalSerialMapper.class);
        InventoryMapper inventory = session.getMapper(InventoryMapper.class);
        var location=session.getMapper(SerialReleaseMapper.class).location(enterpriseId,warehouseId,normalized);
        if(location==null) throw new InventoryException("SERIAL_NOT_FOUND","本地序列号或源桶不存在");
        // 与盘点冻结遵循相同顺序：门禁先于身份/余额；锁后重新验证读到的源桶。
        var gate=inventory.lockGate(enterpriseId,warehouseId,String.valueOf(location.get("location_id")));
        if(gate==null || !InventoryCodes.DECISION_ALLOW.equals(com.lrj.wms.inventory.inventory.domain.InventoryPolicy.decideGate(
                String.valueOf(gate.get("state")),InventoryCodes.CMD_NORMAL_MUTATION)))
            throw new InventoryException("STOCK_FROZEN","源库位门禁不允许序列号调拨发出");
        Map<String, Object> row = locals.lock(enterpriseId, warehouseId, normalized);
        if(row!=null && !location.get("balance_id").equals(row.get("balance_id")))
            throw new InventoryException("VERSION_CONFLICT","源身份在门禁锁定前已移位，重试原释放");
        if (row == null) {
            throw new InventoryException("SERIAL_NOT_FOUND", "本地没有该序列号");
        }
        if (STATE_SEALED.equals(String.valueOf(row.get("state")))) {
            if (transferId.equals(String.valueOf(row.get("transfer_id")))
                    && releaseRef.equals(String.valueOf(row.get("source_release_ref")))
                    && expectedEpoch==asLong(row.get("owner_epoch"))) {
                SerialReleaseRecoveryService.stage(session,clock,enterpriseId,warehouseId,row,transferId,expectedEpoch,releaseRef,true);
                return view(row);
            }
            throw new InventoryException("SERIAL_OPERATION_MISMATCH", "源仓封闭引用与已记录不一致");
        }
        if (expectedEpoch != asLong(row.get("owner_epoch"))) {
            throw new InventoryException("STALE_EPOCH", "旧归属代际不能封闭当前本地授权");
        }
        if (!SerialReceiptService.STATE_AUTHORIZED.equals(String.valueOf(row.get("state")))) {
            throw new InventoryException("SERIAL_STATE_CONFLICT", "当前本地状态不能封闭");
        }
        String balanceId = row.get("balance_id") == null ? null : String.valueOf(row.get("balance_id"));
        if (balanceId == null) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "源仓序列号没有绑定余额");
        }
        Map<String, Object> balance = inventory.lockBalanceById(enterpriseId, warehouseId, balanceId);
        if (balance == null) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "源仓余额不存在");
        }
        if(decimal(balance.get("reserved_qty")).signum()>0 || decimal(balance.get("free_execution_claim_qty")).signum()>0)
            throw new InventoryException("RESERVATION_CONFLICT","源桶已有占用，不能猜测该序列号未被预占");
        // 一个释放引用只能解释一条已封闭身份；不能借其他身份的流水跳过本次扣减。
        if (inventory.countLedger(enterpriseId, warehouseId, releaseRef) != 0)
            throw new InventoryException("SERIAL_OPERATION_MISMATCH","释放引用已被其他库存动作使用");
        {
            if (inventory.casAdjust(enterpriseId, warehouseId, balanceId, new BigDecimal("-1"), BigDecimal.ZERO,
                    BigDecimal.ZERO, asLong(balance.get("version")), now) != 1) {
                throw new InventoryException("RESERVATION_CONFLICT", "源仓封闭后不足覆盖预占");
            }
            Map<String, Object> after = inventory.lockBalanceById(enterpriseId, warehouseId, balanceId);
            inventory.insertLedger(UUID.randomUUID().toString(), enterpriseId, warehouseId, releaseRef, 1, balanceId,
                    new BigDecimal("-1"), BigDecimal.ZERO, BigDecimal.ZERO, decimal(after.get("on_hand_qty")),
                    decimal(after.get("reserved_qty")), decimal(after.get("free_execution_claim_qty")),
                    asLong(after.get("version")), "TRANSFER_ISSUE", transferId, "TRANSFER", now, now);
        }
        if (locals.casSeal(enterpriseId, warehouseId, normalized, SerialReceiptService.STATE_AUTHORIZED, STATE_SEALED,
                transferId, releaseRef, "TRANSFER_PREPARED", expectedEpoch, now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "源仓封闭竞争");
        }
        SerialReleaseRecoveryService.stage(session,clock,enterpriseId,warehouseId,row,transferId,expectedEpoch,releaseRef,false);
        return view(locals.lock(enterpriseId, warehouseId, normalized));
    }

    /** 源仓 SEALED 后忽略旧授权观察，不得改回 AUTHORIZED。 */
    public Map<String, Object> applyObservedAuthorization(String enterpriseId, String warehouseId, String serial,
            long observedEpoch, String observedState) {
        String normalized = SerialReceiptService.normalize(serial);
        LocalSerialMapper locals = session.getMapper(LocalSerialMapper.class);
        Map<String, Object> row = locals.lock(enterpriseId, warehouseId, normalized);
        if (row == null) {
            throw new InventoryException("SERIAL_NOT_FOUND", "本地没有该序列号");
        }
        if (STATE_SEALED.equals(String.valueOf(row.get("state")))) {
            return view(row);
        }
        if (observedEpoch < asLong(row.get("owner_epoch"))) {
            throw new InventoryException("STALE_EPOCH", "旧归属代际不能覆盖当前本地授权");
        }
        locals.updateState(enterpriseId, warehouseId, normalized,
                row.get("balance_id") == null ? null : String.valueOf(row.get("balance_id")),
                String.valueOf(row.get("state")), observedState, null, asLong(row.get("owner_epoch")),
                Timestamp.from(clock.instant()));
        return view(locals.lock(enterpriseId, warehouseId, normalized));
    }

    /**
     * 目的仓接收。先记 HOLD，再向登记申请 RECEIVING/ACTIVE。同操作重放不加量。
     * 登记未在途时保持 HOLD，不放行 AUTHORIZED。
     */
    public Map<String, Object> receiveDestination(String enterpriseId, String warehouseId, String operationId,
            String documentId, String actorId, String serial, StockBucketKey bucket, String transferId,
            long expectedFromEpoch) {
        return receiveDestination(enterpriseId,warehouseId,operationId,documentId,actorId,serial,bucket,transferId,expectedFromEpoch,true);
    }

    /** 目的HOLD与原始转移上下文先落库，登记请求由独立恢复执行器提交。 */
    public Map<String,Object> stageDestination(String e,String w,String op,String document,String actor,String serial,StockBucketKey bucket,String transfer,long epoch) {
        return receiveDestination(e,w,op,document,actor,serial,bucket,transfer,epoch,false);
    }
    private Map<String,Object> receiveDestination(String enterpriseId,String warehouseId,String operationId,String documentId,
            String actorId,String serial,StockBucketKey bucket,String transferId,long expectedFromEpoch,boolean synchronize) {
        SerialRecoveryService.requireWritable(session,enterpriseId,warehouseId);
        requireId(transferId, "INVALID_TRANSFER", "转移标识不能为空");
        String normalized = SerialReceiptService.normalize(serial);
        if (!InventoryCodes.QUALITY_HOLD.equals(bucket.qualityCode())) {
            throw new InventoryException("INVALID_QUALITY", "序列号转移接收必须进入 HOLD 桶");
        }
        if (!enterpriseId.equals(bucket.enterpriseId()) || !warehouseId.equals(bucket.warehouseId())) {
            throw new InventoryException("SCOPE_MISMATCH", "序列号接收范围与桶不一致");
        }
        Timestamp now = Timestamp.from(clock.instant());
        LocalSerialMapper locals = session.getMapper(LocalSerialMapper.class);
        locals.insertIgnore(UUID.randomUUID().toString(), enterpriseId, warehouseId, normalized, bucket.skuId(),
                bucket.lotId(), null, SerialReceiptService.STATE_INTENDED, operationId,
                SerialReceiptService.REGISTRY_NONE, null, now);
        Map<String, Object> row = locals.lock(enterpriseId, warehouseId, normalized);
        if (row == null) {
            throw new InventoryException("VERSION_CONFLICT", "目的序列号意向竞争");
        }
        if (!operationId.equals(String.valueOf(row.get("receipt_operation_id")))
                || !bucket.skuId().equals(row.get("sku_id")) || !bucket.lotId().equals(row.get("lot_id"))
                || row.get("transfer_id") != null && !transferId.equals(row.get("transfer_id"))) {
            throw new InventoryException("SERIAL_ALREADY_RECEIVED", "序列号已被其他收货操作占用");
        }
        SerialReceiptService.requireIdentityBucket(session,enterpriseId,warehouseId,row,bucket);
        if (!java.util.Set.of("INTENDED","HOLD_RECEIVED","EXCEPTION","RECEIVING","AUTHORIZED").contains(row.get("state")))
            throw new InventoryException("SERIAL_STATE_CONFLICT", "当前本地生命周期不能接收转移");
        if (SerialReceiptService.STATE_INTENDED.equals(String.valueOf(row.get("state")))) {
            new InventoryApplicationService(session, clock).receive(enterpriseId, warehouseId, operationId, documentId,
                    actorId, bucket, Quantity.parse("1", 0));
            Map<String, Object> balance = session.getMapper(InventoryMapper.class).lockBalanceByDimension(enterpriseId,
                    warehouseId, bucket.ownerId(), bucket.locationId(), bucket.skuId(), bucket.lotId(),
                    bucket.qualityCode());
            if (balance == null) {
                throw new InventoryException("VERSION_CONFLICT", "HOLD 收货后找不到余额");
            }
            locals.updateState(enterpriseId, warehouseId, normalized, String.valueOf(balance.get("id")),
                    SerialReceiptService.STATE_HOLD_RECEIVED, SerialReceiptService.REGISTRY_NONE, null, 0L, now);
            row = locals.lock(enterpriseId, warehouseId, normalized);
        }
        // 在远程调用前固定原始转移与fromEpoch；恢复不能从后来变化的owner_epoch推测。
        if (row.get("transfer_id") == null) {
            if(locals.bindTransfer(enterpriseId,warehouseId,normalized,transferId,now)!=1)
                throw new InventoryException("VERSION_CONFLICT","转移意图绑定竞争");
            row=locals.lock(enterpriseId,warehouseId,normalized);
        }
        SerialRecoveryService.stage(session,clock,enterpriseId,warehouseId,row,transferId,expectedFromEpoch);
        if(SerialReceiptService.STATE_AUTHORIZED.equals(row.get("state"))) return view(row);
        return synchronize ? syncDestination(enterpriseId, warehouseId, operationId, normalized, bucket.skuId(), transferId,
                expectedFromEpoch, row) : view(row);
    }

    private Map<String, Object> syncDestination(String enterpriseId, String warehouseId, String operationId,
            String serial, String skuId, String transferId, long expectedFromEpoch, Map<String, Object> current) {
        LocalSerialMapper locals = session.getMapper(LocalSerialMapper.class);
        Timestamp now = Timestamp.from(clock.instant());
        String balanceId = current.get("balance_id") == null ? null : String.valueOf(current.get("balance_id"));
        try {
            Map<String, Object> receiving = registry.startReceiving(enterpriseId, skuId, serial, transferId, warehouseId,
                    operationId, expectedFromEpoch);
            locals.updateState(enterpriseId, warehouseId, serial, balanceId, STATE_RECEIVING,
                    String.valueOf(receiving.get("state")), null, asLong(receiving.get("ownerEpoch")), now);
            try {
                Map<String, Object> active = registry.confirmDestination(enterpriseId, skuId, serial, transferId,
                        warehouseId, operationId);
                locals.updateState(enterpriseId, warehouseId, serial, balanceId, SerialReceiptService.STATE_AUTHORIZED,
                        String.valueOf(active.get("state")), null, asLong(active.get("ownerEpoch")), now);
            } catch (SerialRegistryUnavailableException error) {
                locals.updateState(enterpriseId, warehouseId, serial, balanceId, STATE_RECEIVING,
                        STATE_RECEIVING, SerialReceiptService.REGISTRY_UNAVAILABLE,
                        asLong(receiving.get("ownerEpoch")), now);
            } catch (SerialRegistryConflictException error) {
                locals.updateState(enterpriseId, warehouseId, serial, balanceId, STATE_RECEIVING,
                        STATE_RECEIVING, error.code(), asLong(receiving.get("ownerEpoch")), now);
            }
        } catch (SerialRegistryUnavailableException error) {
            locals.updateState(enterpriseId, warehouseId, serial, balanceId, SerialReceiptService.STATE_HOLD_RECEIVED,
                    SerialReceiptService.REGISTRY_NONE, SerialReceiptService.REGISTRY_UNAVAILABLE, 0L, now);
        } catch (SerialRegistryConflictException error) {
            locals.updateState(enterpriseId, warehouseId, serial, balanceId, SerialReceiptService.STATE_HOLD_RECEIVED,
                    SerialReceiptService.REGISTRY_NONE, error.code(), 0L, now);
        }
        return view(locals.lock(enterpriseId, warehouseId, serial));
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

    private static void requireId(String value, String code, String message) {
        if (value == null || value.isBlank() || value.length()>64) {
            throw new InventoryException(code, message);
        }
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal qty) {
            return qty;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

}
