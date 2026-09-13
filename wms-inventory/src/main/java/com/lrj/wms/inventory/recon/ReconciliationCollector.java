package com.lrj.wms.inventory.recon;

import com.lrj.wms.inventory.jobs.JobRunException;
import com.lrj.wms.runtime.db.DatabaseInstants;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import com.lrj.wms.runtime.messaging.SourceWindowService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.apache.ibatis.session.SqlSessionFactory;

/** 两个来源先正向核验原回执，再反向核验库存集合；全部检查点和证明持久化，网络不持有事务。 */
public final class ReconciliationCollector {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ReconciliationCollector.class);
    private static final List<String> SOURCES=List.of("wms-inbound","wms-outbound");
    private final SqlSessionFactory sessions;
    private final Clock clock;
    private final ReconciliationSourcePort source;
    public ReconciliationCollector(SqlSessionFactory sessions,Clock clock,ReconciliationSourcePort source) {
        this.sessions=sessions;this.clock=clock;this.source=source;
    }
    public enum Phase { COLLECT, FACTS, POSTINGS, COMPLETE }
    /** 常量大小的进度；两个完成来源各保留一个摘要，历史事实只存命令唯一投影。 */
    public record Progress(int version,int sourceIndex,Phase phase,long providerCount,ReconciliationEvidence.Cursor cursor,
            String postingAfter,long postingCount,String postingDigest,List<String> sources,List<String> postings) { }

    /** 每仓常驻调度继续活动采集；无活动时继续最近完整窗口的有界对账扫描。 */
    public String scheduledWindow(String e,String w) {
        try(var session=sessions.openSession(false)) {return session.getMapper(ReconciliationCollectionMapper.class).active(e,w);}
    }

    /** 请求不能携带水位或进度；来源、检查点初值和权限范围由服务器确定。 */
    public Map<String,Object> request(String e,String w,String id,Instant cutoff,String actor) {
        try(var session=sessions.openSession(false)) {
            var initial=initial(e,w,id,cutoff,0,List.of(),List.of());
            var result=new ReconciliationCollectionStore(session,clock).request(e,w,id,cutoff,json(initial),actor);
            session.commit();return result;
        }
    }

    /** 每次只推进一个有界阶段；由现有XXL任务重复触发，不在内存维持无限重试循环。 */
    public void advance(String e,String w,String id) {
        if(source==null) throw new JobRunException("SOURCE_UNAVAILABLE","未配置受信来源调用器");
        Map<String,Object> claimed;
        try(var session=sessions.openSession(false)) {
            claimed=new ReconciliationCollectionStore(session,clock).claim(e,w,id);session.commit();
        }
        if(claimed==null) return;
        long epoch=number(claimed,"claim_epoch");
        String original=String.valueOf(claimed.get("collection_progress"));
        try {
            var progress=RuntimeMessage.JSON.readValue(original,Progress.class);
            if(progress.version()!=1 || progress.sourceIndex()<0 || progress.sourceIndex()>=2 || progress.phase()==Phase.COMPLETE)
                throw incomplete();
            Instant cutoff=DatabaseInstants.require(claimed.get("closed_at"));
            String service=SOURCES.get(progress.sourceIndex());
            // 所有HTTP发生在领取事务提交之后，远程超时不能长期占用库存连接或范围锁。
            var collected=progress.phase()==Phase.COLLECT?source.collect(service,e,w,id,cutoff):null;
            var page=progress.phase()==Phase.FACTS?source.read(service,e,w,id,cutoff,progress.cursor().after()):null;
            try(var session=sessions.openSession(false)) {
                var store=new ReconciliationCollectionStore(session,clock);
                if(store.currentClaim(e,w,id,epoch)==null) return;
                var mapper=session.getMapper(ReconciliationCollectionMapper.class);
                Progress next;
                boolean advanced=true;
                if(progress.phase()==Phase.COLLECT) {
                    if(collected.factCount()<progress.providerCount()) throw incomplete();
                    advanced=collected.complete() || collected.factCount()>progress.providerCount();
                    next=new Progress(1,progress.sourceIndex(),collected.complete()?Phase.FACTS:Phase.COLLECT,collected.factCount(),
                            progress.cursor(),null,0,progress.postingDigest(),progress.sources(),progress.postings());
                } else if(progress.phase()==Phase.FACTS) {
                    if(page==null || page.facts()==null || page.facts().size()>200) throw incomplete();
                    var rows=page.facts().isEmpty()?List.<Map<String,Object>>of():mapper.originals(e,w,service,page.facts().stream().map(SourceWindowService.Fact::commandId).toList());
                    var originals=rows.stream().map(row -> new ReconciliationEvidence.LocalFact(e,w,service,text(row,"command_id"),text(row,"action"),text(row,"state"),
                            text(row,"posting_id"),text(row,"posting_action"),(BigDecimal)row.get("quantity"),text(row,"source_execution_id"),row.get("posted_at")==null?null:DatabaseInstants.require(row.get("posted_at")))).toList();
                    var verified=ReconciliationEvidence.verify(new ReconciliationEvidence.Scope(service,e,w,id,cutoff),progress.cursor(),page,originals);
                    saveFacts(mapper,e,w,service,page,rows);
                    next=new Progress(1,progress.sourceIndex(),verified.complete()?Phase.POSTINGS:Phase.FACTS,progress.providerCount(),verified,
                            null,0,progress.postingDigest(),progress.sources(),progress.postings());
                } else {
                    var rows=mapper.postingPage(e,w,service,Timestamp.from(cutoff),progress.postingAfter());
                    String after=progress.postingAfter(),digest=progress.postingDigest();long count=progress.postingCount();
                    for(var row:rows.stream().limit(200).toList()) {
                        String command=text(row,"command_id");
                        if(after!=null && ReconciliationEvidence.compare(command,after)<=0) throw incomplete();
                        digest=ReconciliationEvidence.appendMember(digest,command);count=Math.addExact(count,1);after=command;
                    }
                    if(rows.size()>200) {
                        next=new Progress(1,progress.sourceIndex(),Phase.POSTINGS,progress.providerCount(),progress.cursor(),after,count,digest,progress.sources(),progress.postings());
                    } else {
                        if(!progress.cursor().complete() || count!=progress.cursor().appliedCount() || !digest.equals(progress.cursor().appliedDigest())) throw incomplete();
                        var sourceDigests=new ArrayList<>(progress.sources());sourceDigests.add(progress.cursor().digest());
                        var postingDigests=new ArrayList<>(progress.postings());postingDigests.add(digest);
                        if(progress.sourceIndex()==0) next=initial(e,w,id,cutoff,1,sourceDigests,postingDigests);
                        else {
                            next=new Progress(1,1,Phase.COMPLETE,progress.providerCount(),progress.cursor(),after,count,digest,sourceDigests,postingDigests);
                            // 三个标识由原范围和核验结果生成；完成位与原事实投影同事务，调用者无法自行指定。
                            String sourceToken=RuntimeMessage.hash("SOURCE\n"+json(sourceDigests));
                            String postingToken=RuntimeMessage.hash("POSTING\n"+json(postingDigests));
                            String receiptToken=RuntimeMessage.hash("RECEIPT\n"+json(List.of(sourceToken,postingToken,e,w,id,cutoff.toString())));
                            if(mapper.complete(e,w,id,epoch,json(next),sourceToken,postingToken,receiptToken,Timestamp.from(clock.instant()))!=1
                                    || mapper.release(e,w,id)!=1) throw incomplete();
                            session.commit();return;
                        }
                    }
                }
                if(!store.checkpoint(e,w,id,epoch,json(next),advanced,advanced?null:"SOURCE_PENDING")) throw incomplete();
                session.commit();
            }
        } catch(RuntimeException failure) {
            // 数据库页失败时原事务已回滚，另开短事务记录受控错误；原游标保持不动。
            String code=failure instanceof JobRunException job?job.code():"SOURCE_UNAVAILABLE";
            // 仅记录范围、受控码和异常类型，不输出服务JWT、来源正文或原始SQL参数。
            LOG.warn("reconciliation collection failed enterprise={} warehouse={} cutoff={} epoch={} code={} failureType={}",
                    e,w,id,epoch,code,failure.getClass().getSimpleName());
            try(var session=sessions.openSession(false)) {
                new ReconciliationCollectionStore(session,clock).checkpoint(e,w,id,epoch,original,false,code);session.commit();
            }
        }
    }

    /** 幂等投影不能静默接受冲突的旧内容；只写已匹配原posting的APPLIED事实。 */
    private void saveFacts(ReconciliationCollectionMapper mapper,String e,String w,String service,SourceWindowService.Page page,List<Map<String,Object>> originals) {
        Map<String,String> effects=new HashMap<>();for(var row:originals) effects.put(text(row,"command_id"),text(row,"business_effect_key"));
        var expected=new HashMap<String,ReconciliationCollectionMapper.SourceFact>();
        for(var fact:page.facts()) if("APPLIED".equals(fact.resultState())) {
            expected.put(fact.commandId(),new ReconciliationCollectionMapper.SourceFact(UUID.randomUUID().toString(),fact.commandId(),effects.get(fact.commandId()),
                    new BigDecimal(fact.quantity()),Timestamp.from(Instant.parse(fact.occurredAt())),page.digest()));
        }
        if(expected.isEmpty()) return;
        mapper.insertFacts(e,w,service,List.copyOf(expected.values()),Timestamp.from(clock.instant()));
        for(var row:mapper.facts(e,w,service,List.copyOf(expected.keySet()))) {
            var fact=expected.remove(text(row,"command_id"));
            if(fact==null || !Objects.equals(fact.effectId(),row.get("business_effect_key"))
                    || fact.quantity().compareTo((BigDecimal)row.get("quantity"))!=0
                    || !fact.occurredAt().toInstant().equals(DatabaseInstants.require(row.get("occurred_at")))) throw incomplete();
        }
        if(!expected.isEmpty()) throw incomplete();
    }
    private static Progress initial(String e,String w,String id,Instant cutoff,int index,List<String> sources,List<String> postings) {
        var cursor=ReconciliationEvidence.initial(new ReconciliationEvidence.Scope(SOURCES.get(index),e,w,id,cutoff));
        return new Progress(1,index,Phase.COLLECT,0,cursor,null,0,cursor.appliedDigest(),sources,postings);
    }
    private static String json(Object value) {return RuntimeMessage.JSON.writeValueAsString(value);}
    private static String text(Map<String,Object> row,String key) {return (String)row.get(key);}
    private static long number(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
    private static JobRunException incomplete() {return new JobRunException("SOURCE_INCOMPLETE","三方原凭证或检查点未完整匹配");}
}
