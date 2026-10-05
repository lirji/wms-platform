package com.lrj.wms.runtime.db;

import org.apache.ibatis.annotations.Param;

import java.util.Map;

/** 只访问本物理库的时区元数据，必须在业务服务启动前完成验证。 */
public interface DatabaseTimeMapper {
    /** 读取本 Mapper 定义的表，将 SQL 与绑定参数保持在同一持久化入口。 */
    String sessionOffset();

    /** 读取{@code information_schema}，将 SQL 与绑定参数保持在同一持久化入口。 */
    int policyTableExists();

    /** 读取{@code information_schema}，将 SQL 与绑定参数保持在同一持久化入口。 */
    int existingTables();

    /** 读取{@code database_time_policy}，将 SQL 与绑定参数保持在同一持久化入口。 */
    Map<String, Object> policy();

    /** 写入{@code database_time_policy}、{@code id}，将 SQL 与绑定参数保持在同一持久化入口。 */
    int register(@Param("offset") String offset, @Param("evidence") String evidence);
}
