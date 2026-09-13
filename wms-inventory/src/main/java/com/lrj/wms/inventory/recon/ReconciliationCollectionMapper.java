package com.lrj.wms.inventory.recon;

import java.sql.Timestamp;
import java.util.Map;
import java.util.List;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Param;

/** 采集状态只由持久用例推进，所有网络回执按领取代际比较更新。 */
public interface ReconciliationCollectionMapper {
    /** 与流水共享锁互斥，固定每仓活动窗口及历史上界。 */
    Map<String,Object> guard(@Param("e") String e,@Param("w") String w);
    /** 仓级任务优先活动采集，完成后持续巡检最近窗口，避免余额扫描尚未完成就停止。 */
    String active(@Param("e") String e,@Param("w") String w);
    int activate(@Param("e") String e,@Param("w") String w,@Param("id") String id,@Param("cutoff") Timestamp cutoff);
    int release(@Param("e") String e,@Param("w") String w,@Param("id") String id);
    /** 锁住原窗口，不能由租约接管改写原cutoff。 */
    Map<String,Object> lock(@Param("e") String e,@Param("w") String w,@Param("id") String id);
    int start(@Param("e") String e,@Param("w") String w,@Param("id") String id,@Param("progress") String progress,@Param("now") Timestamp now);
    int claim(@Param("e") String e,@Param("w") String w,@Param("id") String id,@Param("epoch") long epoch,@Param("until") Timestamp until,@Param("now") Timestamp now);
    /** 成功推进检查点后归零连续失败预算；未推进的等待保留计数。 */
    int checkpoint(@Param("e") String e,@Param("w") String w,@Param("id") String id,@Param("epoch") long epoch,
            @Param("progress") String progress,@Param("state") String state,@Param("advanced") boolean advanced,
            @Param("error") String error,@Param("next") Timestamp next,@Param("now") Timestamp now);
    /** 人工重排和取消均使旧回执失效。 */
    int control(@Param("e") String e,@Param("w") String w,@Param("id") String id,@Param("epoch") long epoch,
            @Param("state") String state,@Param("now") Timestamp now);
    int audit(@Param("e") String e,@Param("w") String w,@Param("id") String id,@Param("auditId") String auditId,
            @Param("action") String action,@Param("actor") String actor,@Param("reason") String reason,@Param("epoch") long epoch,@Param("now") Timestamp now);
    /** 单页批量获取原命令及其凭证，避免逐条远程或N+1查询。 */
    List<Map<String,Object>> originals(@Param("e") String e,@Param("w") String w,@Param("source") String source,@Param("commands") List<String> commands);
    List<Map<String,Object>> postingPage(@Param("e") String e,@Param("w") String w,@Param("source") String source,@Param("cutoff") Timestamp cutoff,@Param("after") String after);
    /** 来源投影按命令唯一，重复后仍由用例逐项核对原内容。 */
    int insertFacts(@Param("e") String e,@Param("w") String w,@Param("source") String source,@Param("facts") List<SourceFact> facts,@Param("now") Timestamp now);
    List<Map<String,Object>> facts(@Param("e") String e,@Param("w") String w,@Param("source") String source,@Param("commands") List<String> commands);
    /** 仅在两个来源的正反向核验完成后调用，证明、终态和活动范围释放共用事务。 */
    int complete(@Param("e") String e,@Param("w") String w,@Param("id") String id,@Param("epoch") long epoch,@Param("progress") String progress,
            @Param("source") String source,@Param("posting") String posting,@Param("receipt") String receipt,@Param("now") Timestamp now);
    record SourceFact(String id,String commandId,String effectId,BigDecimal quantity,Timestamp occurredAt,String watermark) { }
}
