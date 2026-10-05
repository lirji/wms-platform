package com.lrj.wms.fulfillment.transfer.persistence;

import com.lrj.wms.contract.serial.selection.SerialExecutionSelection;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.*;

/** 单据原命令和逐SN关联，所有写入由原调拨单锁串行化。 */
public interface SerialTransferMapper {
    /** 读取{@code transfer_serial_command}，将 SQL 与绑定参数保持在同一持久化入口。 */
    Map<String, Object> get(@Param("e") String e, @Param("id") String id);

    /** 读取{@code transfer_serial_command}，将 SQL 与绑定参数保持在同一持久化入口。行锁由调用方事务持有，读取和后续决策必须在同一事务内。 */
    Map<String, Object> lock(@Param("e") String e, @Param("id") String id);

    /** 读取{@code transfer_serial_command}，将 SQL 与绑定参数保持在同一持久化入口。行锁由调用方事务持有，读取和后续决策必须在同一事务内。 */
    Map<String, Object> authorizationCommand(
            @Param("e") String e, @Param("authorization") String authorization);

    /** 写入{@code transfer_serial_command}，将 SQL 与绑定参数保持在同一持久化入口。 */
    int insert(@Param("row") Map<String, Object> row);

    /** 写入{@code transfer_line}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int enable(
            @Param("e") String e, @Param("transfer") String transfer, @Param("line") String line);

    /** 读取{@code transfer_serial_command}，将 SQL 与绑定参数保持在同一持久化入口。 */
    long pendingIssue(
            @Param("e") String e, @Param("transfer") String transfer, @Param("line") String line);

    /** 写入{@code transfer_serial_member}，将 SQL 与绑定参数保持在同一持久化入口。 */
    int members(
            @Param("e") String e,
            @Param("transfer") String transfer,
            @Param("line") String line,
            @Param("id") String id,
            @Param("identities") List<SerialExecutionSelection.Identity> identities,
            @Param("now") Timestamp now);

    /** 读取{@code transfer_serial_member}、{@code transfer_serial_command}，将 SQL 与绑定参数保持在同一持久化入口。行锁由调用方事务持有，读取和后续决策必须在同一事务内。 */
    List<Map<String, Object>> lockMembers(
            @Param("e") String e,
            @Param("transfer") String transfer,
            @Param("serials") List<String> serials);

    /** 写入{@code transfer_serial_member}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int assign(
            @Param("e") String e,
            @Param("transfer") String transfer,
            @Param("id") String id,
            @Param("serials") List<String> serials);

    /** 写入{@code transfer_serial_command}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int complete(@Param("e") String e, @Param("id") String id, @Param("now") Timestamp now);
}
