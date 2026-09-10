package com.lrj.wms.inventory.masterdata;

import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import com.lrj.wms.inventory.masterdata.domain.SkuPolicy;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.apache.ibatis.session.SqlSession;

/**
 * 主数据写入用例。HTTP/OIDC 仍由后续切片接入；本服务拒绝无企业作用域的写入。
 */
public final class MasterdataService {
    private final SqlSession session;
    private final Clock clock;

    public MasterdataService(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    /** 创建仓库；时区必须是IANA标识。 */
    public void createWarehouse(String warehouseId, String enterpriseId, String code, String name, String timezone) {
        SkuPolicy.requireIanaTimezone(timezone);
        mapper().insertWarehouse(warehouseId, enterpriseId, MasterdataCodes.requireCode("仓编码", code),
                MasterdataCodes.requireCode("仓名称", name), timezone, MasterdataCodes.STATE_ACTIVE, now());
    }

    /** 创建库位并同时写入 OPEN 门禁，容量字段必须成对。 */
    public void createLocation(String locationId, String gateId, String enterpriseId, String warehouseId, String code,
            String zoneCode, String locationType, BigDecimal capacityQty, String capacityUnit) {
        if ((capacityQty == null) != (capacityUnit == null || capacityUnit.isBlank())) {
            throw new IllegalArgumentException("库位容量与单位必须成对出现");
        }
        if (capacityQty != null && capacityQty.signum() < 0) {
            throw new IllegalArgumentException("库位容量不能为负");
        }
        Timestamp now = now();
        MasterdataMapper mapper = mapper();
        mapper.insertLocation(locationId, enterpriseId, warehouseId, MasterdataCodes.requireCode("库位编码", code),
                MasterdataCodes.requireCode("库区编码", zoneCode), MasterdataCodes.requireCode("库位类型", locationType),
                capacityQty, blankToNull(capacityUnit), MasterdataCodes.STATE_ACTIVE, now);
        mapper.insertGate(gateId, enterpriseId, warehouseId, locationId, MasterdataCodes.GATE_OPEN, null, 0L, null, now);
    }

    /** 创建商品及基础单位 1:1 换算行。 */
    public void createSku(SkuPolicy sku, String unitRowId) {
        Timestamp now = now();
        MasterdataMapper mapper = mapper();
        mapper.insertSku(sku.skuId(), sku.enterpriseId(), sku.code(), sku.name(), sku.baseUnit(), sku.quantityScale(),
                flag(sku.lotEnabled()), flag(sku.serialEnabled()), flag(sku.expiryEnabled()), sku.policyVersion(),
                sku.state(), now);
        mapper.insertSkuUnit(unitRowId, sku.enterpriseId(), sku.skuId(), sku.baseUnit(), BigDecimal.ONE, BigDecimal.ONE,
                sku.policyVersion(), now);
    }

    /** 为当前策略版本追加非基础单位；换算必须能精确落到基础精度。 */
    public void addSkuUnit(SkuPolicy sku, String unitRowId, String unitCode, BigDecimal numerator, BigDecimal denominator,
            BigDecimal sampleQuantity) {
        if (sku.baseUnit().equals(unitCode)) {
            throw new IllegalArgumentException("基础单位已在创建商品时写入");
        }
        sku.toBaseQuantity(sampleQuantity == null ? BigDecimal.ONE : sampleQuantity, numerator, denominator);
        mapper().insertSkuUnit(unitRowId, sku.enterpriseId(), sku.skuId(), MasterdataCodes.requireCode("单位编码", unitCode),
                numerator, denominator, sku.policyVersion(), now());
    }

    /** 为启用批次的商品登记仓级批次；无批次商品不得调用。 */
    public void createLot(SkuPolicy sku, String lotId, String warehouseId, String ownerId, String lotCode,
            String businessLotKey, Instant producedAt, Instant expiresAt, String sourceDate, long expiryRuleVersion) {
        sku.requireLotUsage(lotId);
        sku.requireLotUsage(lotCode);
        sku.requireExpiryFields(producedAt, expiresAt, sourceDate, expiryRuleVersion);
        mapper().insertLot(lotId, sku.enterpriseId(), warehouseId, MasterdataCodes.requireCode("货权主体", ownerId),
                sku.skuId(), lotCode, MasterdataCodes.requireCode("跨仓批次键", businessLotKey), timestamp(producedAt),
                timestamp(expiresAt), blankToNull(sourceDate), expiryRuleVersion, now());
    }

    private MasterdataMapper mapper() {
        return session.getMapper(MasterdataMapper.class);
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static int flag(boolean value) {
        return value ? 1 : 0;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
