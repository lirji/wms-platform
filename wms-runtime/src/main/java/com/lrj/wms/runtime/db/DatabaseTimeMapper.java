package com.lrj.wms.runtime.db;

import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 只访问本物理库的时区元数据，必须在业务服务启动前完成验证。 */
public interface DatabaseTimeMapper {
    String sessionOffset();
    int policyTableExists();
    int existingTables();
    Map<String,Object> policy();
    int register(@Param("offset") String offset, @Param("evidence") String evidence);
}
