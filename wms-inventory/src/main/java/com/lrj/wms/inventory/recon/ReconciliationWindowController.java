package com.lrj.wms.inventory.recon;

import com.lrj.wms.inventory.jobs.JobRunException;
import com.lrj.wms.security.WmsJwtAuthorities;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 请求只固定历史窗口，来源核验在后台执行；调用者不能传入水位或直接标记完成。 */
@RestController
@ConditionalOnProperty(name="wms.reconciliation.collector.enabled",havingValue="true")
@RequestMapping("/api/wms/v1/warehouses/{warehouseId}/reconciliation-windows/{cutoffId}")
public class ReconciliationWindowController {
    private final SqlSessionFactory sessions;
    private final ReconciliationCollector collector;
    public ReconciliationWindowController(SqlSessionFactory sessions,ReconciliationCollector collector) {this.sessions=sessions;this.collector=collector;}
    /** 原cutoffId重复请求仅返回原窗口；必须recon.export及本仓权限。 */
    @PostMapping
    public ResponseEntity<Map<String,Object>> request(@AuthenticationPrincipal Jwt jwt,@PathVariable String warehouseId,@PathVariable String cutoffId,
            @Valid @RequestBody Request body) {
        String e=require(jwt,warehouseId,"recon.export");
        return ResponseEntity.accepted().body(view(collector.request(e,warehouseId,cutoffId,Instant.parse(body.cutoff()),jwt.getSubject())));
    }
    /** 只读持久状态和服务器生成的三个标识，不向前台返回完整检查点或来源正文。 */
    @GetMapping
    public Map<String,Object> get(@AuthenticationPrincipal Jwt jwt,@PathVariable String warehouseId,@PathVariable String cutoffId) {
        String e=require(jwt,warehouseId,"recon.read");
        try(var session=sessions.openSession(false)) {
            var row=session.getMapper(ReconciliationCollectionMapper.class).lock(e,warehouseId,cutoffId);
            if(row==null) throw new JobRunException("CUTOFF_MISSING","采集窗口不存在");
            return view(row);
        }
    }
    /** 隔离任务的审计重排保留原检查点并提升领取代际。 */
    @PostMapping("/retries")
    public Map<String,Object> retry(@AuthenticationPrincipal Jwt jwt,@PathVariable String warehouseId,@PathVariable String cutoffId,@Valid @RequestBody Control body) {
        return control(jwt,warehouseId,cutoffId,body,"RETRY");
    }
    /** 取消只停止后台采集，历史冻结和已提交业务效果保留。 */
    @PostMapping("/cancellations")
    public Map<String,Object> cancel(@AuthenticationPrincipal Jwt jwt,@PathVariable String warehouseId,@PathVariable String cutoffId,@Valid @RequestBody Control body) {
        return control(jwt,warehouseId,cutoffId,body,"CANCEL");
    }
    private Map<String,Object> control(Jwt jwt,String w,String id,Control body,String action) {
        String e=require(jwt,w,"recon.remediate");
        if(!(body.expectedClaimEpoch() instanceof Long || body.expectedClaimEpoch() instanceof Integer))
            throw new IllegalArgumentException("领取代际必须为精确整数");
        try(var session=sessions.openSession(false)) {
            new ReconciliationCollectionStore(session,Clock.systemUTC()).control(e,w,id,body.expectedClaimEpoch().longValue(),action,jwt.getSubject(),body.reason());
            var result=view(session.getMapper(ReconciliationCollectionMapper.class).lock(e,w,id));session.commit();return result;
        }
    }
    private static String require(Jwt jwt,String w,String scope) {
        WmsJwtAuthorities.requireScope(jwt,scope);WmsJwtAuthorities.requireWarehouse(jwt,w);return WmsJwtAuthorities.enterpriseId(jwt);
    }
    private static Map<String,Object> view(Map<String,Object> row) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("cutoffId",row.get("cutoff_id"));result.put("cutoff",com.lrj.wms.runtime.db.DatabaseInstants.require(row.get("closed_at")).toString());
        result.put("state",row.get("collection_state"));result.put("claimEpoch",row.get("claim_epoch"));result.put("attempts",row.get("collection_attempts"));
        result.put("errorCode",row.get("collection_error"));
        boolean complete=((Number)row.get("evidence_version")).intValue()==1 && ((Number)row.get("watermarks_complete")).intValue()==1
                && "COMPLETE".equals(row.get("collection_state"));
        result.put("watermarksComplete",complete);
        if(complete) {result.put("sourceWatermark",row.get("source_watermark"));result.put("postingWatermark",row.get("posting_watermark"));result.put("receiptWatermark",row.get("receipt_watermark"));}
        return result;
    }
    public record Request(@NotBlank @Size(max=64) String cutoff) { }
    public record Control(@NotNull @PositiveOrZero Number expectedClaimEpoch,@NotBlank @Size(max=512) String reason) { }
    @ExceptionHandler({com.lrj.wms.security.ScopeForbiddenException.class,com.lrj.wms.security.WarehouseForbiddenException.class})
    ResponseEntity<Map<String,Object>> forbidden() {return ResponseEntity.status(403).body(error("WAREHOUSE_FORBIDDEN","缺少操作或仓范围权限"));}
    @ExceptionHandler({JobRunException.class,IllegalArgumentException.class})
    ResponseEntity<Map<String,Object>> invalid(RuntimeException failure) {
        String code=failure instanceof JobRunException job?job.code():"INVALID_CUTOFF";
        int status="CUTOFF_MISSING".equals(code)?404:Set.of("VERSION_CONFLICT","WINDOW_ACTIVE").contains(code)?409:400;
        return ResponseEntity.status(status).body(error(code,"关窗请求、原身份或当前状态不允许此操作"));
    }
    @ExceptionHandler(org.apache.ibatis.exceptions.PersistenceException.class)
    ResponseEntity<Map<String,Object>> database(Exception failure) {return new com.lrj.wms.runtime.web.RuntimeErrors().database(failure);}
    private static Map<String,Object> error(String code,String message) {return Map.of("code",code,"message",message,"retryable",false);}
}
