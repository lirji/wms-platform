package com.lrj.wms.inventory.serial.persistence;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.Map;

/** 源仓释放的独立持久恢复队列，所有读写固定原企业仓。 */
public interface SerialReleaseMapper {
    /** 仅用于决定先锁哪个门禁；锁后必须再次验证本地身份仍在该桶。 */
    Map<String, Object> location(
            @Param("e") String e, @Param("w") String w, @Param("serial") String serial);

    /** 写入{@code serial_release_intent}、{@code id}，将 SQL 与绑定参数保持在同一持久化入口。 */
    int insert(@Param("row") Map<String, Object> row);

    /** 读取{@code serial_release_intent}，将 SQL 与绑定参数保持在同一持久化入口。行锁由调用方事务持有，读取和后续决策必须在同一事务内。 */
    Map<String, Object> lock(@Param("e") String e, @Param("w") String w, @Param("id") String id);

    /** 读取{@code serial_release_intent}、{@code SKIP}，将 SQL 与绑定参数保持在同一持久化入口。行锁由调用方事务持有，读取和后续决策必须在同一事务内。使用既定分页或批量上限，避免一次读取无界数据。 */
    Map<String, Object> next(
            @Param("e") String e, @Param("w") String w, @Param("now") Timestamp now);

    /** 写入{@code serial_release_intent}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int claim(
            @Param("e") String e,
            @Param("w") String w,
            @Param("id") String id,
            @Param("epoch") long epoch,
            @Param("until") Timestamp until,
            @Param("now") Timestamp now);

    /** 写入{@code serial_release_intent}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int finish(
            @Param("e") String e,
            @Param("w") String w,
            @Param("id") String id,
            @Param("epoch") long epoch,
            @Param("state") String state,
            @Param("error") String error,
            @Param("next") Timestamp next,
            @Param("now") Timestamp now);

    /** 人工核查后仅重新授予预算，原业务事实不可变。 */
    int requeue(
            @Param("e") String e,
            @Param("w") String w,
            @Param("id") String id,
            @Param("epoch") long epoch,
            @Param("now") Timestamp now);
}
