package com.lrj.wms.inventory.recon;

import com.lrj.wms.inventory.jobs.JobRunException;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import com.lrj.wms.runtime.messaging.SourceWindowService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** 纯核验规则：来源清单与库存原凭证逐项对应，持久采集器负责原子提交返回的检查点。 */
public final class ReconciliationEvidence {
    private ReconciliationEvidence() { }
    public record Scope(String source,String enterprise,String warehouse,String cutoffId,Instant cutoff) { }
    /** 只存有界摘要/游标，不在每个窗口复制整个来源历史。 */
    public record Cursor(String after,long count,String digest,long expectedCount,String expectedDigest,
            long appliedCount,String appliedDigest,boolean complete) { }
    /** 持久层按原企业、仓、来源和命令批量查询；不存在posting的终态也须返回真实command。 */
    public record LocalFact(String enterprise,String warehouse,String source,String commandId,String action,String state,
            String postingId,String postingAction,BigDecimal quantity,String executionId,Instant postedAt) { }

    /** 初值覆盖完整关窗范围，跨来源或跨仓摘要不能互换。 */
    public static Cursor initial(Scope scope) {
        String digest=SourceWindowService.initialDigest(scope.source(),scope.enterprise(),scope.warehouse(),scope.cutoffId(),scope.cutoff());
        return new Cursor(null,0,digest,-1,null,0,RuntimeMessage.hash(digest+"\nAPPLIED"),false);
    }

    /** 匹配一页并验证最终完整性；任何错误都不返回可推进的检查点。 */
    public static Cursor verify(Scope scope,Cursor cursor,SourceWindowService.Page page,List<LocalFact> localFacts) {
        if(cursor.complete() || page==null || page.schemaVersion()!=1 || !scope.source().equals(page.sourceService())
                || !scope.enterprise().equals(page.enterpriseId()) || !scope.warehouse().equals(page.warehouseId())
                || !scope.cutoffId().equals(page.cutoffId()) || !scope.cutoff().toString().equals(page.cutoff())
                || page.facts()==null || page.facts().size()>200 || page.factCount()<0
                || page.digest()==null || !page.digest().matches("[a-f0-9]{64}")) throw incomplete();
        if(cursor.expectedDigest()!=null && (!cursor.expectedDigest().equals(page.digest()) || cursor.expectedCount()!=page.factCount())) throw incomplete();
        Map<String,LocalFact> originals=new HashMap<>();
        for(var local:localFacts) {
            if(!scope.enterprise().equals(local.enterprise()) || !scope.warehouse().equals(local.warehouse())
                    || !scope.source().equals(local.source()) || originals.put(local.commandId(),local)!=null) throw incomplete();
        }
        String after=cursor.after(),digest=cursor.digest(),members=cursor.appliedDigest();
        long count=cursor.count(),applied=cursor.appliedCount();
        for(var fact:page.facts()) {
            if(fact==null || !validId(fact.commandId()) || !validId(fact.action()) || !validId(fact.executionId())
                    || after!=null && compare(fact.commandId(),after)<=0) throw incomplete();
            var local=originals.remove(fact.commandId());
            if(local==null || fact.resultState()==null || !fact.action().equals(local.action()) || !fact.resultState().equals(local.state())) throw incomplete();
            BigDecimal physical=quantity(fact.quantity()),posted=quantity(fact.postedQuantity());
            try {if(!Instant.parse(fact.occurredAt()).isBefore(scope.cutoff())) throw incomplete();}
            catch(java.time.DateTimeException|NullPointerException invalid) {throw incomplete();}
            if("APPLIED".equals(fact.resultState())) {
                if(!fact.action().equals(local.postingAction()) || !validId(fact.postingId()) || !fact.postingId().equals(local.postingId()) || physical.compareTo(posted)!=0
                        || local.quantity()==null || posted.compareTo(local.quantity())!=0 || !fact.executionId().equals(local.executionId())
                        || local.postedAt()==null || !local.postedAt().isBefore(scope.cutoff())) throw incomplete();
                members=appendMember(members,fact.commandId());applied=Math.addExact(applied,1);
            } else if(!Set.of("REJECTED","CANCELLED").contains(fact.resultState()) || posted.signum()!=0
                    || fact.postingId()!=null || local.postingId()!=null) throw incomplete();
            digest=SourceWindowService.append(digest,fact);count=Math.addExact(count,1);after=fact.commandId();
        }
        if(!originals.isEmpty() || count>page.factCount()) throw incomplete();
        boolean complete=page.nextCursor()==null;
        if(!complete && (page.facts().isEmpty() || !after.equals(page.nextCursor()))) throw incomplete();
        if(complete && (count!=page.factCount() || !digest.equals(page.digest()))) throw incomplete();
        return new Cursor(after,count,digest,page.factCount(),page.digest(),applied,members,complete);
    }

    /** 反向扫描本库同来源截止前posting，逐个追加原command成员后与来源APPLIED集合比较。 */
    public static String appendMember(String previous,String commandId) {
        if(!validId(commandId)) throw incomplete();
        return RuntimeMessage.hash(previous+"\n"+RuntimeMessage.JSON.writeValueAsString(List.of(commandId)));
    }
    /** UTF-8按字节序与数据库utf8mb4_bin的代码点排序一致，避免UTF-16代理项改变游标顺序。 */
    public static int compare(String left,String right) {
        return Arrays.compareUnsigned(left.getBytes(StandardCharsets.UTF_8),right.getBytes(StandardCharsets.UTF_8));
    }
    private static boolean validId(String id) {return id!=null && !id.isBlank() && id.length()<=64;}
    private static BigDecimal quantity(String value) {
        try {
            if(value==null || value.length()>27 || !value.matches("(?:0|[1-9][0-9]{0,19})(?:\\.[0-9]{1,6})?")) throw incomplete();
            BigDecimal quantity=new BigDecimal(value);
            if(quantity.signum()<0 || quantity.precision()>20 || quantity.scale()>6
                    || !quantity.stripTrailingZeros().toPlainString().equals(value)) throw incomplete();
            return quantity;
        } catch(NumberFormatException|NullPointerException invalid) {throw incomplete();}
    }
    private static JobRunException incomplete() {return new JobRunException("SOURCE_INCOMPLETE","原来源事实与库存凭证未完整匹配");}
}
