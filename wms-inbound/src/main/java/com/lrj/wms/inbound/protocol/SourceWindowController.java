package com.lrj.wms.inbound.protocol;

import com.lrj.wms.runtime.messaging.SourceWindowService;
import com.lrj.wms.security.WmsJwtAuthorities;
import com.lrj.wms.security.ScopeForbiddenException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 本服务只签发自己持有的来源证明，受信服务才可冻结时间边界。 */
@RestController
@ConditionalOnBean(SqlSessionFactory.class)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="wms.reconciliation.window-enabled",havingValue="true")
@RequestMapping("/internal/wms/v1/warehouses/{warehouseId}/reconciliation-windows/{cutoffId}")
public final class SourceWindowController {
    private final SqlSessionFactory sessions;
    private final java.util.Set<String> allowed;
    public SourceWindowController(SqlSessionFactory sessions,org.springframework.core.env.Environment environment) {
        this.sessions=sessions;
        this.allowed=Arrays.stream(environment.getProperty("wms.reconciliation.allowed-subjects","").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    /** 每次推进一页；202明确表示来源回执或后续页面尚未齐全。 */
    @PostMapping
    public org.springframework.http.ResponseEntity<Map<String,Object>> collect(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String warehouseId,@PathVariable String cutoffId,@jakarta.validation.Valid @RequestBody WindowRequest body) {
        String e=require(jwt,warehouseId);
        try(var session=sessions.openSession(false)) {
            var result=new SourceWindowService(session,Clock.systemUTC(),"wms-inbound").collect(e,warehouseId,cutoffId,Instant.parse(body.cutoff()));
            Map<String,Object> response=Map.of("schemaVersion",1,"sourceService","wms-inbound","enterpriseId",e,
                    "warehouseId",warehouseId,"cutoffId",cutoffId,"cutoff",body.cutoff(),"state",result.get("state"),
                    "factCount",result.get("fact_count"),"digest",result.get("digest"));
            session.commit();
            return org.springframework.http.ResponseEntity.status("COMPLETE".equals(result.get("state"))?200:202).body(response);
        }
    }
    /** 只读页仍校验受信主体及范围；游标不能绕过服务端固定关闭时刻。 */
    @GetMapping("/facts")
    public SourceWindowService.Page facts(@AuthenticationPrincipal Jwt jwt,@PathVariable String warehouseId,
            @PathVariable String cutoffId,@RequestParam String cutoff,@RequestParam(required=false) String cursor) {
        String e=require(jwt,warehouseId);
        try(var session=sessions.openSession(false)) {
            return new SourceWindowService(session,Clock.systemUTC(),"wms-inbound").read(e,warehouseId,cutoffId,Instant.parse(cutoff),cursor);
        }
    }
    /** 内部路径不经过公开API权限过滤器，显式将服务主体/仓越权转换为403。 */
    @ExceptionHandler({ScopeForbiddenException.class,com.lrj.wms.security.WarehouseForbiddenException.class})
    org.springframework.http.ResponseEntity<Map<String,Object>> forbidden() {
        return org.springframework.http.ResponseEntity.status(403).body(Map.of("code","SOURCE_EVIDENCE_FORBIDDEN","message","缺少来源证明权限或仓范围","retryable",false));
    }
    /** 无效时刻和不可替换窗口属于调用错误，不暴露内部异常。 */
    @ExceptionHandler(IllegalArgumentException.class)
    org.springframework.http.ResponseEntity<Map<String,Object>> invalid() {return new com.lrj.wms.runtime.web.RuntimeErrors().invalidBody();}
    /** 数据库失败保持可重试，不能返回已完成。 */
    @ExceptionHandler(org.apache.ibatis.exceptions.PersistenceException.class)
    org.springframework.http.ResponseEntity<Map<String,Object>> database(Exception failure) {return new com.lrj.wms.runtime.web.RuntimeErrors().database(failure);}
    /** 尚未齐全的来源事实返回409，接收方保留检查点并稍后重试。 */
    @ExceptionHandler(com.lrj.wms.runtime.messaging.SourceWindowPendingException.class)
    org.springframework.http.ResponseEntity<Map<String,Object>> pending() {
        return org.springframework.http.ResponseEntity.status(409).body(Map.of("code","SOURCE_INCOMPLETE","message","来源回执或后续页尚未齐全","retryable",true));
    }
    private String require(Jwt jwt,String warehouse) {
        WmsJwtAuthorities.requireScope(jwt,"recon.evidence");WmsJwtAuthorities.requireWarehouse(jwt,warehouse);
        if(!allowed.contains(jwt.getSubject())) throw new ScopeForbiddenException("recon.evidence");
        return WmsJwtAuthorities.enterpriseId(jwt);
    }
    /** 关闭时刻不可由重放请求替换；其他范围来自路径与服务令牌。 */
    public record WindowRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=64) String cutoff) { }
}
