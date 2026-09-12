package com.lrj.wms.inventory.serial;

import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 登记意图仅在本企业仓范围访问，业务身份不可更新。 */
public interface SerialRecoveryMapper {
    /** 共享路由锁与迁移停写互斥，只覆盖短本地事务。 */
    String routeState(@Param("e") String e,@Param("w") String w);
    int insert(@Param("row") Map<String,Object> row);
    Map<String,Object> lock(@Param("e") String enterprise,@Param("w") String warehouse,@Param("id") String id);
    Map<String,Object> next(@Param("e") String enterprise,@Param("w") String warehouse,@Param("now") Timestamp now);
    int claim(@Param("e") String enterprise,@Param("w") String warehouse,@Param("id") String id,@Param("epoch") long epoch,@Param("until") Timestamp until,@Param("now") Timestamp now);
    int finish(@Param("e") String enterprise,@Param("w") String warehouse,@Param("id") String id,@Param("epoch") long epoch,@Param("state") String state,@Param("error") String error,@Param("next") Timestamp next,@Param("now") Timestamp now);
    /** 查询只返回恢复元数据，稳定时间/ID分页且限定企业仓。 */
    java.util.List<Map<String,Object>> page(@Param("e") String e,@Param("w") String w,@Param("state") String state,@Param("page") com.lrj.wms.runtime.web.CursorPage page);
    int audit(@Param("row") Map<String,Object> row);
    Map<String,Object> lockAudit(@Param("e") String e,@Param("w") String w,@Param("command") String command);
    int requeue(@Param("e") String e,@Param("w") String w,@Param("id") String id,@Param("epoch") long epoch,@Param("now") Timestamp now);
}
