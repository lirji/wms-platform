package com.lrj.wms.runtime.messaging.persistence;

import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 各服务只读写自己物理库内的通用Inbox，不访问其他服务业务表。 */
public interface RuntimeInboxMapper {
    Map<String, Object> lockIdentity(@Param("key") String key);
    int insert(@Param("row") Map<String, Object> row);
    Map<String, Object> lockNext(@Param("now") Timestamp now);
    int claim(@Param("id") String id, @Param("epoch") long epoch, @Param("until") Timestamp until, @Param("now") Timestamp now);
    Map<String, Object> lockClaim(@Param("id") String id, @Param("epoch") long epoch);
    int finish(@Param("id") String id, @Param("epoch") long epoch, @Param("status") String status,
            @Param("error") String error, @Param("next") Timestamp next, @Param("now") Timestamp now);
}
