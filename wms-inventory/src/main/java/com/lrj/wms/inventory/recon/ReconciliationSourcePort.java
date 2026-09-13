package com.lrj.wms.inventory.recon;

import com.lrj.wms.runtime.messaging.SourceWindowService.Page;
import java.time.Instant;

/** 来源只提供本服务原事实；库存必须独立匹配本库凭证后才能完成窗口。 */
public interface ReconciliationSourcePort {
    /** 每次推进一个来源页；进度增加与等待回执分开，避免大窗口耗尽失败重试预算。 */
    Collection collect(String source,String enterprise,String warehouse,String cutoffId,Instant cutoff);
    /** 已完成来源的原事实页，调用器仍须验证范围、顺序和最终摘要。 */
    Page read(String source,String enterprise,String warehouse,String cutoffId,Instant cutoff,String cursor);
    record Collection(boolean complete,long factCount) { }
}
