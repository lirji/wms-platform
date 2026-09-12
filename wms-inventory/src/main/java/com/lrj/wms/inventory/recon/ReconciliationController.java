package com.lrj.wms.inventory.recon;

import com.lrj.wms.inventory.jobs.JobRunException;
import com.lrj.wms.inventory.query.InventoryHttpJson;
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
            @RequestParam(name = "cutoffId") String cutoffId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        if (warehouseIds == null || warehouseIds.size() != 1) {
            throw new IllegalArgumentException("当前查询必须且只能指定一个仓库");
        }
        return listCases(jwt, warehouseIds.getFirst(), cutoffId, cursor, limit);
    }

    @GetMapping("/warehouses/{warehouseId}/reconciliation-cases")
    public Map<String, Object> casesByWarehouse(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @RequestParam(name = "cutoffId") String cutoffId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return listCases(jwt, warehouseId, cutoffId, cursor, limit);
    }

    @GetMapping("/warehouses/{warehouseId}/reconciliation-cases/{id}")
    public Map<String, Object> caseByWarehouse(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String id) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            Map<String, Object> row = session.getMapper(ReconciliationMapper.class)
                    .lockCase(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, id);
            if (row == null) {
                throw new IllegalArgumentException("差异单不存在");
            }
            return InventoryHttpJson.body(row);
        }
    }

    @PostMapping("/reconciliation-cases/{id}/remediations")
    public ResponseEntity<Map<String, Object>> remediate(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @RequestParam(name = "warehouseId") String warehouseId, @jakarta.validation.Valid @RequestBody RemediationRequest body) {
        return remediateCase(jwt, warehouseId, id, body);
    }

    @PostMapping("/warehouses/{warehouseId}/reconciliation-cases/{id}/remediations")
    public ResponseEntity<Map<String, Object>> remediateByWarehouse(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String warehouseId, @PathVariable String id, @jakarta.validation.Valid @RequestBody RemediationRequest body) {
        return remediateCase(jwt, warehouseId, id, body);
    }

    private Map<String, Object> listCases(Jwt jwt, String warehouseId, String cutoffId, String cursor, Integer limit) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        var page = com.lrj.wms.runtime.web.CursorPage.parse(limit, cursor,
                com.lrj.wms.runtime.web.CursorPage.scope("recon", WmsJwtAuthorities.enterpriseId(jwt), warehouseId, cutoffId));
        try (SqlSession session = sessions.openSession()) {
            return InventoryHttpJson.body(page.result(session.getMapper(ReconciliationMapper.class)
                    .listCasesPage(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, cutoffId, page), false));
        }
    }

    private ResponseEntity<Map<String, Object>> remediateCase(Jwt jwt, String warehouseId, String id,
            RemediationRequest body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            Map<String, Object> result = new StockInternalReconcile(session, Clock.systemUTC()).remediate(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, id, body.approvedAction(),
                    body.reason(), longValue(body.expectedVersion()), jwt.getSubject());
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



    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static Map<String, Object> errorBody(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("requestId", com.lrj.wms.runtime.observability.RequestCorrelationFilter.currentId());
        body.put("retryable", false);
        return body;
    }
}
