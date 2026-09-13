package com.lrj.wms.inventory.recon;

import com.lrj.wms.inventory.jobs.JobRunException;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import com.lrj.wms.runtime.messaging.SourceWindowService;
import com.lrj.wms.runtime.messaging.SourceWindowService.Fact;
import com.lrj.wms.runtime.messaging.SourceWindowService.Page;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 核验算法的失败边界；真实数据库、领取代际和HTTP接线另由集成测试承担。 */
class ReconciliationEvidenceTest {
    private static final Instant CUTOFF=Instant.parse("2026-09-12T00:00:00Z");
    private static final ReconciliationEvidence.Scope SCOPE=new ReconciliationEvidence.Scope("wms-outbound","E","W","C",CUTOFF);
    private static Fact fact(String id) {return new Fact(id,"SHIP","EX-"+id,"1","1","P-"+id,CUTOFF.minusSeconds(60).toString(),"APPLIED");}
    private static ReconciliationEvidence.LocalFact local(Fact fact) {
        return new ReconciliationEvidence.LocalFact("E","W","wms-outbound",fact.commandId(),fact.action(),fact.resultState(),
                fact.postingId(),fact.postingId()==null?null:fact.action(),new BigDecimal(fact.postedQuantity()),fact.executionId(),CUTOFF.minusSeconds(30));
    }
    private static Page page(List<Fact> all,List<Fact> chunk,String next) {
        String digest=ReconciliationEvidence.initial(SCOPE).digest();
        for(var fact:all) digest=SourceWindowService.append(digest,fact);
        return new Page(1,SCOPE.source(),"E","W","C",CUTOFF.toString(),all.size(),digest,chunk,next);
    }
    @Test void serializedCheckpointResumes201FactsAndMatchesReverseMembers() {
        var facts=new ArrayList<Fact>();for(int n=0;n<201;n++) facts.add(fact("CMD-%03d".formatted(n)));
        var first=ReconciliationEvidence.verify(SCOPE,ReconciliationEvidence.initial(SCOPE),page(facts,facts.subList(0,200),"CMD-199"),
                facts.subList(0,200).stream().map(ReconciliationEvidenceTest::local).toList());
        assertFalse(first.complete());assertEquals(200,first.count());
        var restored=RuntimeMessage.JSON.readValue(RuntimeMessage.JSON.writeValueAsString(first),ReconciliationEvidence.Cursor.class);
        var complete=ReconciliationEvidence.verify(SCOPE,restored,page(facts,facts.subList(200,201),null),List.of(local(facts.getLast())));
        String reverse=ReconciliationEvidence.initial(SCOPE).appliedDigest();
        for(var fact:facts) reverse=ReconciliationEvidence.appendMember(reverse,fact.commandId());
        assertTrue(complete.complete());assertEquals(201,complete.appliedCount());assertEquals(reverse,complete.appliedDigest());
        assertNotEquals(reverse,ReconciliationEvidence.appendMember(reverse,"EXTRA"));
        assertThrows(JobRunException.class,() -> ReconciliationEvidence.verify(SCOPE,restored,page(facts,List.of(facts.getFirst()),null),List.of(local(facts.getFirst()))));
    }
    @Test void missingChangedScopeWrongExecutionLatePostingAndForgedQuantityCannotComplete() {
        Fact fact=fact("CMD");var page=page(List.of(fact),List.of(fact),null);var initial=ReconciliationEvidence.initial(SCOPE);
        assertThrows(JobRunException.class,() -> ReconciliationEvidence.verify(SCOPE,initial,page,List.of()));
        for(var local:List.of(
                new ReconciliationEvidence.LocalFact("E","W","wms-outbound","CMD","SHIP","APPLIED","P-CMD","RECEIVE",BigDecimal.ONE,"EX-CMD",CUTOFF.minusSeconds(1)),
                new ReconciliationEvidence.LocalFact("E","OTHER","wms-outbound","CMD","SHIP","APPLIED","P-CMD","SHIP",BigDecimal.ONE,"EX-CMD",CUTOFF.minusSeconds(1)),
                new ReconciliationEvidence.LocalFact("E","W","wms-outbound","CMD","SHIP","APPLIED","P-CMD","SHIP",BigDecimal.ONE,"OTHER",CUTOFF.minusSeconds(1)),
                new ReconciliationEvidence.LocalFact("E","W","wms-outbound","CMD","SHIP","APPLIED","P-CMD","SHIP",BigDecimal.ONE,"EX-CMD",CUTOFF)))
            assertThrows(JobRunException.class,() -> ReconciliationEvidence.verify(SCOPE,initial,page,List.of(local)));
        var forged=new Fact("CMD","SHIP","EX-CMD","1E+999999999","1","P-CMD",fact.occurredAt(),"APPLIED");
        assertThrows(JobRunException.class,() -> ReconciliationEvidence.verify(SCOPE,initial,page(List.of(forged),List.of(forged),null),List.of(local(fact))));
        var wrongWindow=new Page(1,SCOPE.source(),"E","W","OTHER",CUTOFF.toString(),1,page.digest(),List.of(fact),null);
        assertThrows(JobRunException.class,() -> ReconciliationEvidence.verify(SCOPE,initial,wrongWindow,List.of(local(fact))));
    }
    @Test void nonPostingTerminalNeedsRealMatchingCommandAndNoStockPosting() {
        var fact=new Fact("CMD","SHIP","EX-CMD","3","0",null,CUTOFF.minusSeconds(60).toString(),"CANCELLED");
        var page=page(List.of(fact),List.of(fact),null);var initial=ReconciliationEvidence.initial(SCOPE);
        var complete=ReconciliationEvidence.verify(SCOPE,initial,page,List.of(local(fact)));
        assertTrue(complete.complete());assertEquals(0,complete.appliedCount());
        var posted=new ReconciliationEvidence.LocalFact("E","W","wms-outbound","CMD","SHIP","CANCELLED","P-CMD","SHIP",BigDecimal.ONE,"EX-CMD",CUTOFF.minusSeconds(30));
        assertThrows(JobRunException.class,() -> ReconciliationEvidence.verify(SCOPE,initial,page,List.of(posted)));
    }
}
