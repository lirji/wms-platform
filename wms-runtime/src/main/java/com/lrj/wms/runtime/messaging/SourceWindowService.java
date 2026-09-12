package com.lrj.wms.runtime.messaging;

import com.lrj.wms.runtime.messaging.persistence.SourceWindowMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.SqlSession;

/** 关窗凭证来自来源命令及实际T3回执；调用方不能自行声明已追平。 */
public final class SourceWindowService {
    private final SqlSession session;
    private final Clock clock;
    private final String source;
    public SourceWindowService(SqlSession session,Clock clock,String source) {
        if(!List.of("wms-inbound","wms-outbound").contains(source)) throw new IllegalArgumentException("未知事实来源");
        this.session=session;this.clock=clock;this.source=source;
    }

    /** 冻结时间边界后每次核对200项，缺T3保留原检查点，下次继续；调用方负责提交事务。 */
    public Map<String,Object> collect(String e,String w,String id,Instant cutoff) {
        validate(e,w,id,cutoff);
        var mapper=session.getMapper(SourceWindowMapper.class);
        mapper.ensureGuard(e,w);mapper.lockGuard(e,w);
        mapper.freeze(e,w,Timestamp.from(cutoff));
        mapper.create(e,w,id,Timestamp.from(cutoff),initialDigest(source,e,w,id,cutoff));
        var window=mapper.lock(e,w,id);
        requireCutoff(window,cutoff);
        if("COMPLETE".equals(window.get("state"))) return window;
        String after=(String)window.get("last_command_id"),digest=(String)window.get("digest");
        long count=((Number)window.get("fact_count")).longValue();
        var rows=mapper.page(e,w,Timestamp.from(cutoff),after);
        for(var row:rows.stream().limit(200).toList()) {
            // 缺命令原文或库存回执不能借数量相等推断成功，历史不完整数据必须修复来源。
            if(!"APPLIED".equals(row.get("state")) || row.get("posting_id")==null || row.get("physical_qty")==null
                    || row.get("posted_qty")==null || decimal(row.get("physical_qty")).compareTo(decimal(row.get("posted_qty")))!=0
                    || !(row.get("has_posting_context") instanceof Number context) || context.intValue()!=1) return window;
            Fact fact=fact(row);digest=append(digest,fact);count++;after=fact.commandId();
        }
        String state=rows.size()<=200?"COMPLETE":"COLLECTING";
        if(mapper.advance(e,w,id,((Number)window.get("version")).longValue(),after,count,digest,state)!=1)
            throw new IllegalStateException("来源关窗检查点竞争");
        return mapper.lock(e,w,id);
    }

    /** 仅输出已完整关窗的不可变事实页，接收方必须核对最终数量和链式摘要。 */
    public Page read(String e,String w,String id,Instant cutoff,String after) {
        validate(e,w,id,cutoff);
        if(after!=null && (after.isBlank() || after.length()>64)) throw new IllegalArgumentException("无效事实游标");
        var mapper=session.getMapper(SourceWindowMapper.class);var window=mapper.lock(e,w,id);
        requireCutoff(window,cutoff);
        if(!"COMPLETE".equals(window.get("state"))) throw new SourceWindowPendingException();
        var rows=mapper.page(e,w,Timestamp.from(cutoff),after);
        var facts=new ArrayList<Fact>();for(var row:rows.stream().limit(200).toList()) facts.add(fact(row));
        return new Page(1,source,e,w,id,cutoff.toString(),((Number)window.get("fact_count")).longValue(),
                (String)window.get("digest"),facts,rows.size()>200?facts.getLast().commandId():null);
    }
    /** 摘要覆盖来源、范围、关闭时刻和有序事实，禁止拿另一仓或另一窗口的回执替代。 */
    public static String initialDigest(String source,String e,String w,String id,Instant cutoff) {
        return RuntimeMessage.hash(RuntimeMessage.JSON.writeValueAsString(List.of(1,source,e,w,id,cutoff.toString())));
    }
    /** 固定数组顺序，不依赖某个JSON库对对象属性的枚举顺序。 */
    public static String append(String digest,Fact fact) {
        return RuntimeMessage.hash(digest+"\n"+RuntimeMessage.JSON.writeValueAsString(List.of(fact.commandId(),fact.action(),fact.executionId(),
                fact.quantity(),fact.postedQuantity(),fact.postingId(),fact.occurredAt())));
    }
    private void validate(String e,String w,String id,Instant cutoff) {
        for(String value:List.of(e,w,id)) if(value.isBlank() || value.length()>64) throw new IllegalArgumentException("无效关窗范围");
        if(cutoff==null || cutoff.isAfter(clock.instant()) || cutoff.getNano()%1000!=0) throw new IllegalArgumentException("关窗时刻必须为过去且精度不超过微秒");
    }
    private static void requireCutoff(Map<String,Object> window,Instant cutoff) {
        if(window==null || !instant(window.get("closed_at")).equals(cutoff)) throw new IllegalArgumentException("原关窗身份和时刻不可替换");
    }
    private static Fact fact(Map<String,Object> row) {
        return new Fact((String)row.get("command_id"),(String)row.get("action"),(String)row.get("source_execution_id"),
                decimal(row.get("physical_qty")).stripTrailingZeros().toPlainString(),decimal(row.get("posted_qty")).stripTrailingZeros().toPlainString(),
                (String)row.get("posting_id"),instant(row.get("executed_at")).toString());
    }
    private static BigDecimal decimal(Object value) {return value instanceof BigDecimal d?d:new BigDecimal(value.toString());}
    private static Instant instant(Object value) {
        return com.lrj.wms.runtime.db.DatabaseInstants.require(value);
    }
    /** 原命令、物理量和已确认库存回执构成同一证明，不接受外部业务DTO。 */
    public record Fact(String commandId,String action,String executionId,String quantity,String postedQuantity,String postingId,String occurredAt) { }
    /** 页正文与最终证明分开，丢失任意一页都无法通过最终摘要。 */
    public record Page(int schemaVersion,String sourceService,String enterpriseId,String warehouseId,String cutoffId,String cutoff,
            long factCount,String digest,List<Fact> facts,String nextCursor) { }
}
