package com.lrj.wms.runtime.messaging.persistence;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 来源关窗只读本服务事实；分页检查点与证明摘要同事务保存。 */
public interface SourceWindowMapper {
    /** 与已升级来源T1使用同一范围锁；旧写节点退出前不得启用关窗入口。 */
    int ensureGuard(@Param("e") String e,@Param("w") String w);
    Map<String,Object> lockGuard(@Param("e") String e,@Param("w") String w);
    int freeze(@Param("e") String e,@Param("w") String w,@Param("cutoff") Timestamp cutoff);
    int create(@Param("e") String e,@Param("w") String w,@Param("id") String id,@Param("cutoff") Timestamp cutoff,@Param("digest") String digest);
    Map<String,Object> lock(@Param("e") String e,@Param("w") String w,@Param("id") String id);
    /** 固定关闭时刻，使用稳定主键；一次只读取201行用于判断是否还有下一页。 */
    List<Map<String,Object>> page(@Param("e") String e,@Param("w") String w,@Param("cutoff") Timestamp cutoff,@Param("after") String after);
    int advance(@Param("e") String e,@Param("w") String w,@Param("id") String id,@Param("version") long version,
            @Param("after") String after,@Param("count") long count,@Param("digest") String digest,@Param("state") String state);
}
