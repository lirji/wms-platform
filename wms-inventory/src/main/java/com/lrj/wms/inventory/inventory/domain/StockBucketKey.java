package com.lrj.wms.inventory.inventory.domain;

import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 库存桶唯一维度。无批次必须用非空 sentinel {@link MasterdataCodes#NO_LOT}，禁止 NULL 键。
 * 容器不纳入首期余额维度。锁顺序按稳定键排序。
 */
public final class StockBucketKey implements Comparable<StockBucketKey> {
    private static final Comparator<StockBucketKey> ORDER = Comparator
            .comparing(StockBucketKey::enterpriseId)
            .thenComparing(StockBucketKey::warehouseId)
            .thenComparing(StockBucketKey::ownerId)
            .thenComparing(StockBucketKey::locationId)
            .thenComparing(StockBucketKey::skuId)
            .thenComparing(StockBucketKey::lotId)
            .thenComparing(StockBucketKey::qualityCode);

    private final String enterpriseId;
    private final String warehouseId;
    private final String ownerId;
    private final String locationId;
    private final String skuId;
    private final String lotId;
    private final String qualityCode;

    private StockBucketKey(String enterpriseId, String warehouseId, String ownerId, String locationId, String skuId,
            String lotId, String qualityCode) {
        this.enterpriseId = enterpriseId;
        this.warehouseId = warehouseId;
        this.ownerId = ownerId;
        this.locationId = locationId;
        this.skuId = skuId;
        this.lotId = lotId;
        this.qualityCode = qualityCode;
    }

    /** 创建桶键；lot 不得为空，质量必须是封闭集合。 */
    public static StockBucketKey of(String enterpriseId, String warehouseId, String ownerId, String locationId,
            String skuId, String lotId, String qualityCode) {
        return new StockBucketKey(
                MasterdataCodes.requireCode("企业标识", enterpriseId),
                MasterdataCodes.requireCode("仓库标识", warehouseId),
                MasterdataCodes.requireCode("货权主体", ownerId),
                MasterdataCodes.requireCode("库位标识", locationId),
                MasterdataCodes.requireCode("商品标识", skuId),
                MasterdataCodes.requireCode("批次标识", lotId),
                InventoryCodes.requireQuality(qualityCode));
    }

    /** 同类对象按稳定桶键排序后加锁，避免死锁。 */
    public static List<StockBucketKey> lockOrder(List<StockBucketKey> keys) {
        return keys.stream().distinct().sorted().toList();
    }

    public String enterpriseId() {
        return enterpriseId;
    }

    public String warehouseId() {
        return warehouseId;
    }

    public String ownerId() {
        return ownerId;
    }

    public String locationId() {
        return locationId;
    }

    public String skuId() {
        return skuId;
    }

    public String lotId() {
        return lotId;
    }

    public String qualityCode() {
        return qualityCode;
    }

    @Override
    public int compareTo(StockBucketKey other) {
        return ORDER.compare(this, other);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof StockBucketKey other)) {
            return false;
        }
        return enterpriseId.equals(other.enterpriseId)
                && warehouseId.equals(other.warehouseId)
                && ownerId.equals(other.ownerId)
                && locationId.equals(other.locationId)
                && skuId.equals(other.skuId)
                && lotId.equals(other.lotId)
                && qualityCode.equals(other.qualityCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enterpriseId, warehouseId, ownerId, locationId, skuId, lotId, qualityCode);
    }
}
