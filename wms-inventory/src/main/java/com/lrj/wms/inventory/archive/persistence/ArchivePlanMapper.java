package com.lrj.wms.inventory.archive.persistence;

import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/** 归档规划只读不可变流水；全部写入局限于计划及候选清单。 */
public interface ArchivePlanMapper {
    /** 写入{@code archive_plan}、{@code id}，将 SQL 与绑定参数保持在同一持久化入口。 */
    int create(
            @Param("enterprise") String enterprise,
            @Param("warehouse") String warehouse,
            @Param("run") String run,
            @Param("policy") String policy,
            @Param("cutoff") Timestamp cutoff,
            @Param("actor") String actor,
            @Param("id") String id,
            @Param("now") Timestamp now);

    /** 读取{@code archive_plan}，将 SQL 与绑定参数保持在同一持久化入口。行锁由调用方事务持有，读取和后续决策必须在同一事务内。 */
    Map<String, Object> lock(
            @Param("enterprise") String enterprise,
            @Param("warehouse") String warehouse,
            @Param("run") String run);

    /** 读取{@code stock_ledger}，将 SQL 与绑定参数保持在同一持久化入口。使用既定分页或批量上限，避免一次读取无界数据。 */
    @Options(timeout = 5)
    List<Map<String, Object>> candidates(
            @Param("enterprise") String enterprise,
            @Param("warehouse") String warehouse,
            @Param("cutoff") Timestamp cutoff,
            @Param("cursor") String cursor);

    /** 写入{@code archive_plan_item}，将 SQL 与绑定参数保持在同一持久化入口。 */
    int item(
            @Param("enterprise") String enterprise,
            @Param("warehouse") String warehouse,
            @Param("plan") String plan,
            @Param("id") String id,
            @Param("ledger") String ledger,
            @Param("hash") String hash,
            @Param("now") Timestamp now);

    /** 写入{@code archive_plan}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int checkpoint(
            @Param("enterprise") String enterprise,
            @Param("warehouse") String warehouse,
            @Param("id") String id,
            @Param("version") long version,
            @Param("cursor") String cursor,
            @Param("count") int count,
            @Param("hash") String hash,
            @Param("state") String state,
            @Param("now") Timestamp now);
}
