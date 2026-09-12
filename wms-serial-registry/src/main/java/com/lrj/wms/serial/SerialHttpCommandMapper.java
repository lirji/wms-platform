package com.lrj.wms.serial;

import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 内部HTTP命令幂等审计，与登记状态在同一本地事务中提交。 */
public interface SerialHttpCommandMapper {
    /** 同企业命令键固定请求摘要；唯一键竞争后必须读取原摘要核对。 */
    int insert(@Param("id") String id, @Param("enterprise") String enterprise, @Param("warehouse") String warehouse,
            @Param("command") String command, @Param("hash") String hash, @Param("action") String action,
            @Param("actor") String actor, @Param("now") Timestamp now);

    Map<String, Object> lock(@Param("enterprise") String enterprise, @Param("command") String command);

    /** 原业务结果和命令审计同事务保存，失败时不存在虚假的已完成命令。 */
    int finish(@Param("enterprise") String enterprise, @Param("command") String command, @Param("result") String result);
}
