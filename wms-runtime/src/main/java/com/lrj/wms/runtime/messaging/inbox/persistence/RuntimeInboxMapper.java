package com.lrj.wms.runtime.messaging.inbox.persistence;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.Map;

/** 各服务只读写自己物理库内的通用Inbox，不访问其他服务业务表。 */
public interface RuntimeInboxMapper {
    /** 读取{@code runtime_message_inbox}，将 SQL 与绑定参数保持在同一持久化入口。行锁由调用方事务持有，读取和后续决策必须在同一事务内。 */
    Map<String, Object> lockIdentity(@Param("key") String key);

    /** 写入{@code runtime_message_inbox}、{@code id}，将 SQL 与绑定参数保持在同一持久化入口。 */
    int insert(@Param("row") Map<String, Object> row);

    /** 读取{@code runtime_message_inbox}、{@code SKIP}，将 SQL 与绑定参数保持在同一持久化入口。行锁由调用方事务持有，读取和后续决策必须在同一事务内。使用既定分页或批量上限，避免一次读取无界数据。 */
    Map<String, Object> lockNext(@Param("now") Timestamp now);

    /** 写入{@code runtime_message_inbox}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int claim(
            @Param("id") String id,
            @Param("epoch") long epoch,
            @Param("until") Timestamp until,
            @Param("now") Timestamp now);

    /** 读取{@code runtime_message_inbox}，将 SQL 与绑定参数保持在同一持久化入口。行锁由调用方事务持有，读取和后续决策必须在同一事务内。 */
    Map<String, Object> lockClaim(@Param("id") String id, @Param("epoch") long epoch);

    /** 写入{@code runtime_message_inbox}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int finish(
            @Param("id") String id,
            @Param("epoch") long epoch,
            @Param("status") String status,
            @Param("error") String error,
            @Param("next") Timestamp next,
            @Param("now") Timestamp now);
}
