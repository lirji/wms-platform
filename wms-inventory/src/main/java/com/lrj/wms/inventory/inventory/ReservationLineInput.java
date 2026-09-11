package com.lrj.wms.inventory.inventory;

import com.lrj.wms.inventory.inventory.domain.Quantity;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;

/** 一次仓级 TCC Try 的预占明细。同一 attempt 可含多行，但只写一个预占头。 */
public record ReservationLineInput(StockBucketKey bucket, Quantity qty, String orderLineId) {
}
