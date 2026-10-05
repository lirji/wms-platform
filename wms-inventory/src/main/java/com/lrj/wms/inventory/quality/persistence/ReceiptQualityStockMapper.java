package com.lrj.wms.inventory.quality.persistence;

import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Map;

/** 库存侧只信任本库原收货凭证和流水，不能从质检事件制造新库存维度。 */
public interface ReceiptQualityStockMapper {
    /** 读取{@code stock_posting}、{@code stock_ledger}、{@code stock_balance}，将 SQL 与绑定参数保持在同一持久化入口。 */
    Map<String, Object> receipt(
            @Param("ent") String ent, @Param("wh") String wh, @Param("receipt") String receipt);

    /** 写入{@code stock_receipt_quality}、{@code id}，将 SQL 与绑定参数保持在同一持久化入口。 */
    int initialize(
            @Param("id") String id,
            @Param("ent") String ent,
            @Param("wh") String wh,
            @Param("receipt") String receipt,
            @Param("now") Timestamp now);

    /** 读取{@code stock_receipt_quality}，将 SQL 与绑定参数保持在同一持久化入口。行锁由调用方事务持有，读取和后续决策必须在同一事务内。 */
    Map<String, Object> lock(
            @Param("ent") String ent, @Param("wh") String wh, @Param("receipt") String receipt);

    /** 写入{@code stock_receipt_quality}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int apply(
            @Param("ent") String ent,
            @Param("wh") String wh,
            @Param("receipt") String receipt,
            @Param("sourceVersion") long sourceVersion,
            @Param("expected") long expected,
            @Param("accepted") BigDecimal accepted,
            @Param("rejected") BigDecimal rejected,
            @Param("now") Timestamp now);

    /** 写入{@code stock_receipt_quality}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int addPutaway(
            @Param("ent") String ent,
            @Param("wh") String wh,
            @Param("receipt") String receipt,
            @Param("expected") long expected,
            @Param("qty") BigDecimal qty,
            @Param("now") Timestamp now);
}
