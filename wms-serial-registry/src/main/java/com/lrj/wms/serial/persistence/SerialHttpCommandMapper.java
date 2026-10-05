package com.lrj.wms.serial.persistence;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.Map;

/** 内部HTTP命令幂等审计，与登记状态在同一本地事务中提交。 */
public interface SerialHttpCommandMapper {
    /** 同企业命令键固定请求摘要；唯一键竞争后必须读取原摘要核对。 */
    int insert(
            @Param("id") String id,
            @Param("enterprise") String enterprise,
            @Param("warehouse") String warehouse,
            @Param("command") String command,
            @Param("hash") String hash,
            @Param("action") String action,
            @Param("actor") String actor,
            @Param("now") Timestamp now);

    /** 读取{@code serial_http_command}，将 SQL 与绑定参数保持在同一持久化入口。行锁由调用方事务持有，读取和后续决策必须在同一事务内。 */
    Map<String, Object> lock(
            @Param("enterprise") String enterprise, @Param("command") String command);

    /** 原业务结果和命令审计同事务保存，失败时不存在虚假的已完成命令。 */
    int finish(
            @Param("enterprise") String enterprise,
            @Param("command") String command,
            @Param("result") String result);
}
