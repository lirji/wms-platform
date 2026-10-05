package com.lrj.wms.inventory.serial.persistence;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.*;

/** 原调拨命令只在所属仓短事务内写入，结果核验批量读取原恢复意图。 */
public interface SerialTransferCommandMapper {
    /** 写入{@code serial_transfer_command}、{@code id}，将 SQL 与绑定参数保持在同一持久化入口。 */
    int insert(
            @Param("e") String e,
            @Param("w") String w,
            @Param("id") String id,
            @Param("hash") String hash,
            @Param("payload") String payload,
            @Param("now") Timestamp now);

    /** 读取{@code serial_transfer_command}，将 SQL 与绑定参数保持在同一持久化入口。行锁由调用方事务持有，读取和后续决策必须在同一事务内。 */
    Map<String, Object> lock(@Param("e") String e, @Param("w") String w, @Param("id") String id);

    /** 读取{@code serial_transfer_command}、{@code SKIP}，将 SQL 与绑定参数保持在同一持久化入口。行锁由调用方事务持有，读取和后续决策必须在同一事务内。使用既定分页或批量上限，避免一次读取无界数据。 */
    Map<String, Object> next(
            @Param("e") String e, @Param("w") String w, @Param("now") Timestamp now);

    /** 读取{@code serial_release_intent}、{@code serial_recovery_intent}，将 SQL 与绑定参数保持在同一持久化入口。 */
    List<Map<String, Object>> proofs(
            @Param("e") String e,
            @Param("w") String w,
            @Param("source") boolean source,
            @Param("transfer") String transfer,
            @Param("serials") List<String> serials);

    /** 写入{@code serial_transfer_command}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int checked(
            @Param("e") String e,
            @Param("w") String w,
            @Param("id") String id,
            @Param("version") long version,
            @Param("complete") boolean complete,
            @Param("next") Timestamp next,
            @Param("now") Timestamp now);
}
