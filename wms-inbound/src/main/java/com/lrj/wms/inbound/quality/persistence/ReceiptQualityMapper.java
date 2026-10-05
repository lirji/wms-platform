package com.lrj.wms.inbound.quality.persistence;

import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/** 分批质量只查询本库来源事实，锁定数量边界后才发布库存命令。 */
public interface ReceiptQualityMapper {
    /** 读取{@code source_command}、{@code source_effect}、{@code inbound_line}，将 SQL 与绑定参数保持在同一持久化入口。 */
    Map<String, Object> receipt(
            @Param("ent") String ent, @Param("wh") String wh, @Param("receipt") String receipt);

    /** 写入{@code inbound_receipt_quality}、{@code id}，将 SQL 与绑定参数保持在同一持久化入口。 */
    int initialize(
            @Param("id") String id,
            @Param("ent") String ent,
            @Param("wh") String wh,
            @Param("receipt") String receipt,
            @Param("line") String line,
            @Param("now") Timestamp now);

    /** 读取{@code inbound_receipt_quality}，将 SQL 与绑定参数保持在同一持久化入口。行锁由调用方事务持有，读取和后续决策必须在同一事务内。 */
    Map<String, Object> lock(
            @Param("ent") String ent, @Param("wh") String wh, @Param("receipt") String receipt);

    /** 读取{@code inbound_quality_revision}，将 SQL 与绑定参数保持在同一持久化入口。使用既定分页或批量上限，避免一次读取无界数据。 */
    List<Map<String, Object>> revisions(
            @Param("ent") String ent,
            @Param("wh") String wh,
            @Param("receipt") String receipt,
            @Param("inspection") String inspection,
            @Param("command") String command,
            @Param("sourceVersion") long sourceVersion);

    /** 写入{@code inbound_quality_revision}，将 SQL 与绑定参数保持在同一持久化入口。 */
    int insertRevision(
            @Param("ent") String ent,
            @Param("wh") String wh,
            @Param("receipt") String receipt,
            @Param("inspection") String inspection,
            @Param("command") String command,
            @Param("sourceVersion") long sourceVersion,
            @Param("hash") String hash,
            @Param("actor") String actor,
            @Param("accepted") BigDecimal accepted,
            @Param("rejected") BigDecimal rejected,
            @Param("now") Timestamp now);

    /** 写入{@code inbound_receipt_quality}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int accept(
            @Param("ent") String ent,
            @Param("wh") String wh,
            @Param("receipt") String receipt,
            @Param("command") String command,
            @Param("sourceVersion") long sourceVersion,
            @Param("expected") long expected,
            @Param("accepted") BigDecimal accepted,
            @Param("rejected") BigDecimal rejected,
            @Param("now") Timestamp now);

    /** 写入{@code inbound_receipt_quality}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int applied(
            @Param("ent") String ent,
            @Param("wh") String wh,
            @Param("command") String command,
            @Param("state") String state,
            @Param("now") Timestamp now);

    /** 写入{@code inbound_receipt_quality}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int addPutaway(
            @Param("ent") String ent,
            @Param("wh") String wh,
            @Param("receipt") String receipt,
            @Param("expected") long expected,
            @Param("qty") BigDecimal qty,
            @Param("now") Timestamp now);

    /** 写入{@code inbound_task}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int bindTask(
            @Param("ent") String ent,
            @Param("wh") String wh,
            @Param("task") String task,
            @Param("receipt") String receipt,
            @Param("location") String location,
            @Param("now") Timestamp now);

    /** 读取{@code inbound_line}、{@code source_effect}、{@code source_command}，将 SQL 与绑定参数保持在同一持久化入口。使用既定分页或批量上限，避免一次读取无界数据。 */
    List<Map<String, Object>> batches(
            @Param("ent") String ent,
            @Param("wh") String wh,
            @Param("order") String order,
            @Param("page") com.lrj.wms.runtime.web.CursorPage page);
}
