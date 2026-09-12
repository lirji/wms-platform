package com.lrj.wms.inventory.masterdata;

import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import com.lrj.wms.inventory.masterdata.domain.SkuPolicy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** 隔离种子目录：2仓、5类SKU、基础库位、权限映射与开账库存。不发明 OQ-03。 */
public final class SeedCatalog {
    public static final String ENTERPRISE = "ENT-DEMO";
    /** OQ-03 未确认前仅使用单一自营货权占位，不宣称已批准多货主模型。 */
    public static final String OWNER = "OWNER-SELF";
    public static final String WAREHOUSE_A = "WH-A";
    public static final String WAREHOUSE_B = "WH-B";
    public static final String ACTOR = "seed-local";
    public static final String OPENING_DOCUMENT = "SEED-OPENING";

    private SeedCatalog() {
    }

    public record WarehouseSeed(String id, String code, String name, String timezone) {
    }

    public record SkuSeed(SkuPolicy policy, String unitRowId, boolean extraCase) {
    }

    public record StockSeed(String idSuffix, String skuId, String lotIdOrTemplate, String locationSuffix,
            String qualityCode, BigDecimal onHandQty) {
        public String balanceId(String warehouseId) {
            return "BAL-" + warehouseId + "-" + idSuffix;
        }

        public String lotId(String warehouseId) {
            return lotIdOrTemplate.contains("%s") ? lotIdOrTemplate.formatted(warehouseId) : lotIdOrTemplate;
        }

        public String locationId(String warehouseId) {
            return warehouseId + "-" + locationSuffix;
        }
    }

    public static List<WarehouseSeed> warehouses() {
        return List.of(
                new WarehouseSeed(WAREHOUSE_A, "SHA", "上海演示仓", "Asia/Shanghai"),
                new WarehouseSeed(WAREHOUSE_B, "SZX", "深圳演示仓", "Asia/Shanghai"));
    }

    public static List<SkuSeed> skus() {
        return List.of(
                new SkuSeed(SkuPolicy.create("SKU-STD", ENTERPRISE, "SKU-STD", "普通商品", "EA", 0, false, false, false, 1,
                        MasterdataCodes.STATE_ACTIVE), "UNIT-STD-EA", false),
                new SkuSeed(SkuPolicy.create("SKU-LOT", ENTERPRISE, "SKU-LOT", "批次商品", "EA", 0, true, false, false, 1,
                        MasterdataCodes.STATE_ACTIVE), "UNIT-LOT-EA", true),
                new SkuSeed(SkuPolicy.create("SKU-SN", ENTERPRISE, "SKU-SN", "序列号商品", "EA", 0, false, true, false, 1,
                        MasterdataCodes.STATE_ACTIVE), "UNIT-SN-EA", false),
                new SkuSeed(SkuPolicy.create("SKU-NEAR", ENTERPRISE, "SKU-NEAR", "临期商品", "EA", 0, true, false, true, 1,
                        MasterdataCodes.STATE_ACTIVE), "UNIT-NEAR-EA", false),
                new SkuSeed(SkuPolicy.create("SKU-EXPIRED", ENTERPRISE, "SKU-EXPIRED", "过期商品", "EA", 0, true, false, true, 1,
                        MasterdataCodes.STATE_ACTIVE), "UNIT-EXPIRED-EA", false));
    }

    public static Instant nearExpiry(Instant now) {
        return now.plus(Duration.ofDays(7));
    }

    public static Instant alreadyExpired(Instant now) {
        return now.minus(Duration.ofDays(1));
    }

    public static BigDecimal twelve() {
        return new BigDecimal("12");
    }

    /**
     * 开账库存：可分配良品、临期、过期 HOLD。不写序列号实物，避免无登记身份。
     * 数量只在首次插入生效，复跑不得累加。
     */
    public static List<StockSeed> stocks() {
        return List.of(
                new StockSeed("STD-STO-GOOD", "SKU-STD", MasterdataCodes.NO_LOT, "STO", "GOOD",
                        new BigDecimal("120")),
                new StockSeed("LOT-STO-GOOD", "SKU-LOT", "%s-LOT-STD", "STO", "GOOD", new BigDecimal("48")),
                new StockSeed("NEAR-STO-GOOD", "SKU-NEAR", "%s-LOT-NEAR", "STO", "GOOD", new BigDecimal("24")),
                new StockSeed("EXP-STO-HOLD", "SKU-EXPIRED", "%s-LOT-EXP", "STO", "HOLD", new BigDecimal("6")));
    }

    public static String countPlanId(String warehouseId) {
        return "CNT-DEMO-DRAFT-" + warehouseId;
    }

    public static String storageLocation(String warehouseId) {
        return warehouseId + "-STO";
    }
}
