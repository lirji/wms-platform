package com.lrj.wms.inventory.quality;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 库存侧只信任本库原收货凭证和流水，不能从质检事件制造新库存维度。 */
public interface ReceiptQualityStockMapper {
    Map<String, Object> receipt(@Param("ent") String ent, @Param("wh") String wh, @Param("receipt") String receipt);
    int initialize(@Param("id") String id, @Param("ent") String ent, @Param("wh") String wh,
            @Param("receipt") String receipt, @Param("now") Timestamp now);
    Map<String, Object> lock(@Param("ent") String ent, @Param("wh") String wh, @Param("receipt") String receipt);
    int apply(@Param("ent") String ent, @Param("wh") String wh, @Param("receipt") String receipt,
            @Param("sourceVersion") long sourceVersion, @Param("expected") long expected,
            @Param("accepted") BigDecimal accepted, @Param("rejected") BigDecimal rejected, @Param("now") Timestamp now);
}
