package com.lrj.wms.inventory.recon;

import com.lrj.wms.inventory.inventory.domain.ExpiryPolicy;
import com.lrj.wms.inventory.jobs.JobRunException;
import com.lrj.wms.security.WarehouseForbiddenException;
import com.lrj.wms.security.WmsJwtAuthorities;
import java.sql.Timestamp;
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

/** 数量快照导出。服务身份读取，不开放核心库账号。 */
@RestController
@RequestMapping("/api/wms/v1")
@ConditionalOnBean(SqlSessionFactory.class)
public class SnapshotExportController {
    private final SqlSessionFactory sessions;

    public SnapshotExportController(SqlSessionFactory sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/reconciliation-snapshots")
    public ResponseEntity<Map<String, Object>> create(@AuthenticationPrincipal Jwt jwt,
            @RequestBody Map<String, Object> body) {
        List<String> warehouseIds = warehouseIds(body.get("warehouseIds"));
        if (warehouseIds.isEmpty()) {
            throw new IllegalArgumentException("导出必须带仓库");
        }
        String warehouseId = warehouseIds.getFirst();
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            Map<String, Object> result = new SnapshotExportService(session, Clock.systemUTC()).export(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, text(body, "cutoffId"),
                    closedAt(body.get("cutoff")), text(body, "sourceWatermark"), text(body, "postingWatermark"),
                    text(body, "receiptWatermark"));
            session.commit();
            return ResponseEntity.accepted().body(Map.of("snapshotJobId", result.get("snapshotId"), "state",
                    result.get("state")));
        }
    }

    @GetMapping("/reconciliation-snapshots/{id}")
    public Map<String, Object> get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @RequestParam(name = "warehouseId") String warehouseId) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            return new SnapshotExportService(session, Clock.systemUTC()).get(WmsJwtAuthorities.enterpriseId(jwt),
                    warehouseId, id);
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

    private static List<String> warehouseIds(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static Timestamp closedAt(Object value) {
        return value == null ? null : Timestamp.from(ExpiryPolicy.instantOf(value));
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
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
