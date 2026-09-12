package com.lrj.wms.inventory.serial;

import com.lrj.wms.inventory.OnInventoryJdbcConfigured;
import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.runtime.web.CursorPage;
import com.lrj.wms.runtime.observability.RequestCorrelationFilter;
import com.lrj.wms.security.WmsJwtAuthorities;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Clock;
import java.util.Map;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 原始登记恢复状态可查询；人工重排需要恢复scope、仓权限与不可变审计。 */
@RestController
@Conditional(OnInventoryJdbcConfigured.class)
@RequestMapping("/api/wms/v1/warehouses/{warehouseId}/serial-recoveries")
public final class SerialRecoveryController {
    private final SqlSessionFactory sessions;
    public SerialRecoveryController(SqlSessionFactory sessions) { this.sessions=sessions; }
    @GetMapping
    public Map<String,Object> list(@AuthenticationPrincipal Jwt jwt,@PathVariable String warehouseId,
            @RequestParam(required=false) Integer limit,@RequestParam(required=false) String cursor,@RequestParam(required=false) String state) {
        WmsJwtAuthorities.requireWarehouse(jwt,warehouseId); WmsJwtAuthorities.requireScope(jwt,"messaging.read");
        if(state!=null && !java.util.Set.of("PENDING","RUNNING","DONE","ISOLATED","SUPERSEDED").contains(state)) throw new InventoryException("INVALID_ARGUMENT","恢复状态不合法");
        String e=WmsJwtAuthorities.enterpriseId(jwt);
        var page=CursorPage.chronological(limit,cursor,CursorPage.scope("serial-recovery",e,warehouseId,state));
        try(var session=sessions.openSession()) {
            var rows=session.getMapper(SerialRecoveryMapper.class).page(e,warehouseId,state,page);
            var result=page.result(rows,true);
            for(var row:rows) row.put("created_at",com.lrj.wms.runtime.db.DatabaseInstants.require(row.get("created_at")).toString());
            return result;
        }
    }
    @PostMapping("/{intentId}/retries")
    public ResponseEntity<Map<String,Object>> retry(@AuthenticationPrincipal Jwt jwt,@PathVariable String warehouseId,@PathVariable String intentId,
            @RequestHeader("Idempotency-Key") String command,@Valid @RequestBody RetryRequest body) {
        WmsJwtAuthorities.requireWarehouse(jwt,warehouseId); WmsJwtAuthorities.requireScope(jwt,"messaging.recover");
        if(intentId.isBlank() || intentId.length()>64) throw new InventoryException("INVALID_ARGUMENT","恢复意图标识不合法");
        try(var session=sessions.openSession(false)) {
            var result=SerialRecoveryOperations.retry(session,Clock.systemUTC(),WmsJwtAuthorities.enterpriseId(jwt),warehouseId,intentId,command,jwt.getSubject(),body.expectedEpoch(),body.reason());
            session.commit(); return ResponseEntity.accepted().body(result);
        }
    }
    public record RetryRequest(@NotNull @Min(0) Long expectedEpoch,@NotBlank @Size(max=500) String reason) { }
    @ExceptionHandler({com.lrj.wms.security.WarehouseForbiddenException.class,com.lrj.wms.security.ScopeForbiddenException.class})
    ResponseEntity<Map<String,Object>> forbidden() {
        return ResponseEntity.status(403).body(Map.of("code","SERIAL_RECOVERY_FORBIDDEN","message","缺少恢复权限或仓范围","retryable",false,"requestId",RequestCorrelationFilter.currentId()));
    }
    @ExceptionHandler(InventoryException.class)
    ResponseEntity<Map<String,Object>> business(InventoryException failure) {
        int status=failure.code().equals("RESOURCE_NOT_FOUND")?404:failure.code().equals("INVALID_ARGUMENT")?400:409;
        return ResponseEntity.status(status).body(Map.of("code",failure.code(),"message",failure.getMessage(),"retryable",false,"requestId",RequestCorrelationFilter.currentId()));
    }
}
