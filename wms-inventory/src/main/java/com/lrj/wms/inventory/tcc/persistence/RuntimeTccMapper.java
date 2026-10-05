package com.lrj.wms.inventory.tcc.persistence;

import org.apache.ibatis.annotations.Param;

import java.util.Map;

/** RM持久化边界；登记意图先提交，网络在事务外，所有阶段按原身份条件更新。 */
public interface RuntimeTccMapper {
    /** 写入{@code inventory_tcc_intent}、{@code id}，将 SQL 与绑定参数保持在同一持久化入口。 */
    void insert(
            @Param("id") String id,
            @Param("r") com.lrj.wms.contract.tcc.WarehouseTryRequest request,
            @Param("xid") String xid,
            @Param("action") String action,
            @Param("digest") String digest,
            @Param("payload") String payload);

    /** 读取{@code inventory_tcc_intent}，将 SQL 与绑定参数保持在同一持久化入口。行锁由调用方事务持有，读取和后续决策必须在同一事务内。 */
    Map<String, Object> lock(
            @Param("enterprise") String enterprise,
            @Param("warehouse") String warehouse,
            @Param("allocation") String allocation,
            @Param("attempt") String attempt);

    /** 写入{@code inventory_tcc_intent}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int bindBranch(@Param("id") String id, @Param("branch") long branch);

    /** 写入{@code inventory_tcc_intent}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int tried(
            @Param("id") String id,
            @Param("branch") long branch,
            @Param("reservation") String reservation);

    /** 写入{@code inventory_tcc_intent}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int finished(
            @Param("id") String id, @Param("branch") long branch, @Param("state") String state);

    /** 原Fence只按XID和branch读取，不猜测当前资源。 */
    Map<String, Object> fence(@Param("xid") String xid, @Param("branch") long branch);

    /** 写入{@code inventory_tcc_terminal}、{@code id}，将 SQL 与绑定参数保持在同一持久化入口。 */
    void terminal(
            @Param("i") Map<String, Object> intent,
            @Param("status") int status,
            @Param("json") String json,
            @Param("hash") String hash);

    /** 读取{@code inventory_tcc_terminal}，将 SQL 与绑定参数保持在同一持久化入口。 */
    Map<String, Object> proof(@Param("id") String id);

    /** 读取{@code inventory_tcc_terminal}、{@code warehouse_route}，将 SQL 与绑定参数保持在同一持久化入口。使用既定分页或批量上限，避免一次读取无界数据。 */
    java.util.List<String> historicalResources(@Param("cell") String cell);
}
