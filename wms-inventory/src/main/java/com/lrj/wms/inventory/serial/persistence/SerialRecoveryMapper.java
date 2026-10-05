package com.lrj.wms.inventory.serial.persistence;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.Map;

/** 登记意图仅在本企业仓范围访问，业务身份不可更新。 */
public interface SerialRecoveryMapper {
    /** 共享路由锁与迁移停写互斥，只覆盖短本地事务。 */
    String routeState(@Param("e") String e, @Param("w") String w);

    /** 写入{@code serial_recovery_intent}、{@code id}，将 SQL 与绑定参数保持在同一持久化入口。 */
    int insert(@Param("row") Map<String, Object> row);

    /** 读取{@code serial_recovery_intent}，将 SQL 与绑定参数保持在同一持久化入口。行锁由调用方事务持有，读取和后续决策必须在同一事务内。 */
    Map<String, Object> lock(
            @Param("e") String enterprise, @Param("w") String warehouse, @Param("id") String id);

    /** 读取{@code serial_recovery_intent}、{@code SKIP}，将 SQL 与绑定参数保持在同一持久化入口。行锁由调用方事务持有，读取和后续决策必须在同一事务内。使用既定分页或批量上限，避免一次读取无界数据。 */
    Map<String, Object> next(
            @Param("e") String enterprise,
            @Param("w") String warehouse,
            @Param("now") Timestamp now);

    /** 写入{@code serial_recovery_intent}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int claim(
            @Param("e") String enterprise,
            @Param("w") String warehouse,
            @Param("id") String id,
            @Param("epoch") long epoch,
            @Param("until") Timestamp until,
            @Param("now") Timestamp now);

    /** 写入{@code serial_recovery_intent}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int finish(
            @Param("e") String enterprise,
            @Param("w") String warehouse,
            @Param("id") String id,
            @Param("epoch") long epoch,
            @Param("state") String state,
            @Param("error") String error,
            @Param("next") Timestamp next,
            @Param("now") Timestamp now);

    /** 查询只返回恢复元数据，稳定时间/ID分页且限定企业仓。 */
    java.util.List<Map<String, Object>> page(
            @Param("e") String e,
            @Param("w") String w,
            @Param("state") String state,
            @Param("page") com.lrj.wms.runtime.web.CursorPage page);

    /** 写入{@code serial_recovery_audit}、{@code id}，将 SQL 与绑定参数保持在同一持久化入口。 */
    int audit(@Param("row") Map<String, Object> row);

    /** 读取{@code serial_recovery_audit}，将 SQL 与绑定参数保持在同一持久化入口。行锁由调用方事务持有，读取和后续决策必须在同一事务内。 */
    Map<String, Object> lockAudit(
            @Param("e") String e, @Param("w") String w, @Param("command") String command);

    /** 写入{@code serial_recovery_intent}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int requeue(
            @Param("e") String e,
            @Param("w") String w,
            @Param("id") String id,
            @Param("epoch") long epoch,
            @Param("now") Timestamp now);
}
