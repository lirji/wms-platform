package com.lrj.wms.inventory.masterdata;

import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import com.lrj.wms.inventory.masterdata.domain.SkuPolicy;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataHttpMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;

/**
 * 主数据写用例的 HTTP 应用层：幂等键、摘要冲突与回读。
 * 企业级写（仓/商品）用固定仓键 *，不把新仓假装已在 JWT 里。
 */
public final class MasterdataCommandService {
    static final String ENTERPRISE_IDEMPOTENCY_WAREHOUSE = "*";

    private final SqlSession session;
    private final Clock clock;

    public MasterdataCommandService(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    public Map<String, Object> createWarehouse(String enterpriseId, String code, String name, String timezone,
            String clientOperationId) {
        SkuPolicy.requireIanaTimezone(timezone);
        String warehouseId = MasterdataCodes.requireCode("仓编码", code);
        String digest = digest("warehouse", warehouseId, name, timezone);
        return write(enterpriseId, ENTERPRISE_IDEMPOTENCY_WAREHOUSE, clientOperationId, digest, () -> {
            MasterdataHttpMapper http = http();
            Map<String, Object> existing = http.getWarehouseByCode(enterpriseId, warehouseId);
            if (existing != null) {
                throw new MasterdataException("DUPLICATE_DOCUMENT", "仓编码已存在");
            }
            writes().createWarehouse(warehouseId, enterpriseId, warehouseId, name, timezone);
            return warehouseId;
        }, () -> requireWarehouse(enterpriseId, warehouseId));
    }

    public Map<String, Object> createLocation(String enterpriseId, String warehouseId, String code, String zoneCode,
            String locationType, BigDecimal capacityQty, String capacityUnit, String clientOperationId) {
        String locationId = MasterdataCodes.requireCode("库位编码", code);
        String digest = digest("location", warehouseId, locationId, zoneCode, locationType,
                capacityQty == null ? "" : capacityQty.toPlainString(), blank(capacityUnit));
        return write(enterpriseId, warehouseId, clientOperationId, digest, () -> {
            if (http().getLocationByCode(enterpriseId, warehouseId, locationId) != null) {
                throw new MasterdataException("DUPLICATE_DOCUMENT", "库位编码已存在");
            }
            writes().createLocation(locationId, "GATE-" + locationId, enterpriseId, warehouseId, locationId, zoneCode,
                    locationType, capacityQty, blankToNull(capacityUnit));
            return locationId;
        }, () -> requireLocation(enterpriseId, warehouseId, locationId));
    }

    public Map<String, Object> createSku(String enterpriseId, String code, String name, String baseUnit,
            int quantityScale, boolean lotEnabled, boolean serialEnabled, boolean expiryEnabled,
            String clientOperationId) {
        String skuId = MasterdataCodes.requireCode("商品编码", code);
        String digest = digest("sku", skuId, name, baseUnit, String.valueOf(quantityScale), String.valueOf(lotEnabled),
                String.valueOf(serialEnabled), String.valueOf(expiryEnabled));
        return write(enterpriseId, ENTERPRISE_IDEMPOTENCY_WAREHOUSE, clientOperationId, digest, () -> {
            if (http().getSkuByCode(enterpriseId, skuId) != null) {
                throw new MasterdataException("DUPLICATE_DOCUMENT", "商品编码已存在");
            }
            SkuPolicy sku = SkuPolicy.create(skuId, enterpriseId, skuId, name, baseUnit, quantityScale, lotEnabled,
                    serialEnabled, expiryEnabled, 1L, MasterdataCodes.STATE_ACTIVE);
            writes().createSku(sku, skuId + "-" + baseUnit);
            return skuId;
        }, () -> requireSku(enterpriseId, skuId));
    }

    public Map<String, Object> addSkuUnit(String enterpriseId, String skuId, String unitCode, BigDecimal numerator,
            BigDecimal denominator, BigDecimal sampleQuantity, String clientOperationId) {
        SkuPolicy sku = skuPolicy(enterpriseId, requireSku(enterpriseId, skuId));
        String digest = digest("sku-unit", skuId, unitCode, numerator.toPlainString(), denominator.toPlainString(),
                sampleQuantity == null ? "" : sampleQuantity.toPlainString());
        return write(enterpriseId, ENTERPRISE_IDEMPOTENCY_WAREHOUSE, clientOperationId, digest, () -> {
            if (http().getSkuUnit(enterpriseId, skuId, unitCode) != null) {
                throw new MasterdataException("DUPLICATE_DOCUMENT", "单位编码已存在");
            }
            writes().addSkuUnit(sku, skuId + "-" + unitCode, unitCode, numerator, denominator, sampleQuantity);
            return skuId + "-" + unitCode;
        }, () -> {
            Map<String, Object> unit = http().getSkuUnit(enterpriseId, skuId, unitCode);
            if (unit == null) {
                throw new MasterdataException("RESOURCE_NOT_FOUND", "单位不存在");
            }
            return unit;
        });
    }

    public Map<String, Object> createLot(String enterpriseId, String warehouseId, String ownerId, String skuId,
            String lotCode, String businessLotKey, Instant producedAt, Instant expiresAt, String sourceDate,
            long expiryRuleVersion, String clientOperationId) {
        SkuPolicy sku = skuPolicy(enterpriseId, requireSku(enterpriseId, skuId));
        String lotId = MasterdataCodes.requireCode("批次编码", lotCode);
        String digest = digest("lot", warehouseId, ownerId, skuId, lotId, businessLotKey,
                producedAt == null ? "" : producedAt.toString(), expiresAt == null ? "" : expiresAt.toString(),
                blank(sourceDate), String.valueOf(expiryRuleVersion));
        return write(enterpriseId, warehouseId, clientOperationId, digest, () -> {
            if (http().getLot(enterpriseId, warehouseId, lotId) != null) {
                throw new MasterdataException("DUPLICATE_DOCUMENT", "批次已存在");
            }
            writes().createLot(sku, lotId, warehouseId, ownerId, lotId, businessLotKey, producedAt, expiresAt,
                    sourceDate, expiryRuleVersion);
            return lotId;
        }, () -> requireLot(enterpriseId, warehouseId, lotId));
    }

    public Map<String, Object> requireWarehouse(String enterpriseId, String warehouseId) {
        Map<String, Object> row = http().getWarehouse(enterpriseId, warehouseId);
        if (row == null) {
            throw new MasterdataException("WAREHOUSE_NOT_FOUND", "仓库不存在");
        }
        return row;
    }

    public Map<String, Object> requireLocation(String enterpriseId, String warehouseId, String locationId) {
        Map<String, Object> row = http().getLocation(enterpriseId, warehouseId, locationId);
        if (row == null) {
            throw new MasterdataException("LOCATION_NOT_FOUND", "库位不存在");
        }
        return row;
    }

    public Map<String, Object> requireGate(String enterpriseId, String warehouseId, String locationId) {
        Map<String, Object> row = http().getLocationGate(enterpriseId, warehouseId, locationId);
        if (row == null) {
            throw new MasterdataException("GATE_NOT_FOUND", "库位门禁不存在");
        }
        return row;
    }

    public Map<String, Object> requireSku(String enterpriseId, String skuId) {
        Map<String, Object> row = http().getSku(enterpriseId, skuId);
        if (row == null) {
            throw new MasterdataException("SKU_NOT_FOUND", "商品不存在");
        }
        return row;
    }

    public Map<String, Object> requireLot(String enterpriseId, String warehouseId, String lotId) {
        Map<String, Object> row = http().getLot(enterpriseId, warehouseId, lotId);
        if (row == null) {
            throw new MasterdataException("LOT_NOT_FOUND", "批次不存在");
        }
        return row;
    }

    private Map<String, Object> write(String enterpriseId, String warehouseId, String clientOperationId, String digest,
            Creator creator, Reader reader) {
        MasterdataHttpMapper http = http();
        Map<String, Object> existing = http.getIdempotency(enterpriseId, warehouseId, clientOperationId);
        if (existing != null) {
            if (!digest.equals(String.valueOf(existing.get("request_digest")))) {
                throw new MasterdataException("IDEMPOTENCY_PAYLOAD_MISMATCH", "同键异内容拒绝");
            }
            return reader.read();
        }
        String resourceId = creator.create();
        http.insertIdempotency(UUID.randomUUID().toString(), enterpriseId, warehouseId, clientOperationId, digest,
                resourceId, Timestamp.from(clock.instant()));
        Map<String, Object> replay = http.getIdempotency(enterpriseId, warehouseId, clientOperationId);
        if (replay != null && !digest.equals(String.valueOf(replay.get("request_digest")))) {
            throw new MasterdataException("IDEMPOTENCY_PAYLOAD_MISMATCH", "同键异内容拒绝");
        }
        return reader.read();
    }

    private MasterdataService writes() {
        return new MasterdataService(session, clock);
    }

    private MasterdataHttpMapper http() {
        return session.getMapper(MasterdataHttpMapper.class);
    }

    static SkuPolicy skuPolicy(String enterpriseId, Map<String, Object> row) {
        return SkuPolicy.create(text(row, "id"), enterpriseId, text(row, "code"), text(row, "name"),
                text(row, "base_unit"), intValue(row.get("quantity_scale")), flag(row.get("lot_enabled")),
                flag(row.get("serial_enabled")), flag(row.get("expiry_enabled")),
                longValue(row.get("policy_version"), 1L), text(row, "state"));
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static boolean flag(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return "1".equals(String.valueOf(value)) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static long longValue(Object value, long fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String digest(String... parts) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(String.join("\u001f", parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("缺少SHA-256", ex);
        }
    }

    @FunctionalInterface
    private interface Creator {
        String create();
    }

    @FunctionalInterface
    private interface Reader {
        Map<String, Object> read();
    }
}
