package com.lrj.wms.runtime.messaging.window.persistence;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/** 来源关窗只读本服务事实；分页检查点与证明摘要同事务保存。 */
public interface SourceWindowMapper {
    /** 与已升级来源T1使用同一范围锁；旧写节点退出前不得启用关窗入口。 */
    int ensureGuard(@Param("e") String e, @Param("w") String w);

    /** 读取{@code source_window_guard}，将 SQL 与绑定参数保持在同一持久化入口。行锁由调用方事务持有，读取和后续决策必须在同一事务内。 */
    Map<String, Object> lockGuard(@Param("e") String e, @Param("w") String w);

    /** 写入{@code source_window_guard}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int freeze(@Param("e") String e, @Param("w") String w, @Param("cutoff") Timestamp cutoff);

    /** 写入{@code source_reconciliation_window}、{@code cutoff_id}，将 SQL 与绑定参数保持在同一持久化入口。 */
    int create(
            @Param("e") String e,
            @Param("w") String w,
            @Param("id") String id,
            @Param("cutoff") Timestamp cutoff,
            @Param("digest") String digest);

    /** 读取{@code source_reconciliation_window}，将 SQL 与绑定参数保持在同一持久化入口。行锁由调用方事务持有，读取和后续决策必须在同一事务内。 */
    Map<String, Object> lock(@Param("e") String e, @Param("w") String w, @Param("id") String id);

    /** 固定关闭时刻，使用稳定主键；一次只读取201行用于判断是否还有下一页。 */
    List<Map<String, Object>> page(
            @Param("e") String e,
            @Param("w") String w,
            @Param("cutoff") Timestamp cutoff,
            @Param("after") String after);

    /** 写入{@code source_reconciliation_window}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int advance(
            @Param("e") String e,
            @Param("w") String w,
            @Param("id") String id,
            @Param("version") long version,
            @Param("after") String after,
            @Param("count") long count,
            @Param("digest") String digest,
            @Param("state") String state);
}
