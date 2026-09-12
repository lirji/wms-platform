package com.lrj.wms.inventory.recon;

import com.lrj.wms.inventory.jobs.JobRunException;
import com.lrj.wms.security.WarehouseForbiddenException;
import com.lrj.wms.security.WmsJwtAuthorities;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 差异工作台只读列表与审批。前台不执行后台对账巡检。 */
@RestController
@RequestMapping("/api/wms/v1")
@ConditionalOnBean(SqlSessionFactory.class)
public class ReconciliationController {
    private final SqlSessionFactory sessions;

    public ReconciliationController(SqlSessionFactory sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/reconciliation-cases")
    public Map<String, Object> cases(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "warehouseIds") List<String> warehouseIds,
            @RequestParam(name = "cutoffId") String cutoffId) {
        if (warehouseIds == null || warehouseIds.isEmpty()) {
            throw new IllegalArgumentException("查询必须带仓库");
        }
        String warehouseId = warehouseIds.getFirst();
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            List<Map<String, Object>> items = new StockInternalReconcile(session, Clock.systemUTC())
                    .listCases(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, cutoffId);
            return Map.of("items", items, "limit", items.size());
        }
    }

    @PostMapping("/reconciliation-cases/{id}/remediations")
    public ResponseEntity<Map<String, Object>> remediate(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @RequestParam(name = "warehouseId") String warehouseId, @RequestBody Map<String, Object> body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            Map<String, Object> result = new StockInternalReconcile(session, Clock.systemUTC()).remediate(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, id, text(body, "approvedAction"),
                    text(body, "reason"), longValue(body.get("expectedVersion")), jwt.getSubject());
            session.commit();
            return ResponseEntity.accepted().body(result);
        }
    }

    @ExceptionHandler(WarehouseForbiddenException.class)
    ResponseEntity<Map<String, Object>> forbidden(WarehouseForbiddenException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorBody("WAREHOUSE_FORBIDDEN", "无权访问该仓"));
    }

    @ExceptionHandler({IllegalArgumentException.class, JobRunException.class})
    ResponseEntity<Map<String, Object>> badRequest(RuntimeException error) {
        String code = error instanceof JobRunException job ? job.code() : "INVALID_SCOPE";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(code, error.getMessage()));
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static Map<String, Object> errorBody(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("requestId", UUID.randomUUID().toString());
        body.put("retryable", false);
        return body;
    }
}
