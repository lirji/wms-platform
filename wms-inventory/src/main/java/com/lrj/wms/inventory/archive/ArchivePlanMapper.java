package com.lrj.wms.inventory.archive;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Options;

/** 归档规划只读不可变流水；全部写入局限于计划及候选清单。 */
public interface ArchivePlanMapper {
    int create(@Param("enterprise") String enterprise, @Param("warehouse") String warehouse, @Param("run") String run,
            @Param("policy") String policy, @Param("cutoff") Timestamp cutoff, @Param("actor") String actor, @Param("id") String id, @Param("now") Timestamp now);
    Map<String, Object> lock(@Param("enterprise") String enterprise, @Param("warehouse") String warehouse, @Param("run") String run);
    @Options(timeout = 5)
    List<Map<String, Object>> candidates(@Param("enterprise") String enterprise, @Param("warehouse") String warehouse,
            @Param("cutoff") Timestamp cutoff, @Param("cursor") String cursor);
    int item(@Param("enterprise") String enterprise, @Param("warehouse") String warehouse, @Param("plan") String plan,
            @Param("id") String id, @Param("ledger") String ledger, @Param("hash") String hash, @Param("now") Timestamp now);
    int checkpoint(@Param("enterprise") String enterprise, @Param("warehouse") String warehouse, @Param("id") String id,
            @Param("version") long version, @Param("cursor") String cursor, @Param("count") int count,
            @Param("hash") String hash, @Param("state") String state, @Param("now") Timestamp now);
}
