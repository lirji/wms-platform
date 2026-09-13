package com.lrj.wms.inventory.serial;

import java.sql.Timestamp;
import java.util.*;
import org.apache.ibatis.annotations.Param;

/** 原调拨命令只在所属仓短事务内写入，结果核验批量读取原恢复意图。 */
public interface SerialTransferCommandMapper {
    int insert(@Param("e") String e,@Param("w") String w,@Param("id") String id,@Param("hash") String hash,@Param("payload") String payload,@Param("now") Timestamp now);
    Map<String,Object> lock(@Param("e") String e,@Param("w") String w,@Param("id") String id);
    Map<String,Object> next(@Param("e") String e,@Param("w") String w,@Param("now") Timestamp now);
    List<Map<String,Object>> proofs(@Param("e") String e,@Param("w") String w,@Param("source") boolean source,@Param("transfer") String transfer,@Param("serials") List<String> serials);
    int checked(@Param("e") String e,@Param("w") String w,@Param("id") String id,@Param("version") long version,@Param("complete") boolean complete,@Param("next") Timestamp next,@Param("now") Timestamp now);
}
