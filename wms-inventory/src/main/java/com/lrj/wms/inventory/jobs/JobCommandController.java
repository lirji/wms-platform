package com.lrj.wms.inventory.jobs;

import com.lrj.wms.inventory.query.InventoryHttpJson;
import com.lrj.wms.security.WarehouseForbiddenException;
import com.lrj.wms.security.WmsJwtAuthorities;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 任务回收与领取。不重放设备动作，只处理租约与就绪分片。 */
@RestController
@RequestMapping("/api/wms/v1/jobs/{jobId}")
@ConditionalOnBean(SqlSessionFactory.class)
public class JobCommandController {
    private final SqlSessionFactory sessions;

    public JobCommandController(SqlSessionFactory sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/retries")
    public Map<String, Object> retry(@AuthenticationPrincipal Jwt jwt, @PathVariable String jobId,
            @RequestParam(name = "warehouseId") String warehouseId,
            @jakarta.validation.Valid @RequestBody(required = false) JobCommandRequests.RetryRequest body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        String action = body == null || body.action() == null ? "RECLAIM" : String.valueOf(body.action());
        try (SqlSession session = sessions.openSession(false)) {
            String enterpriseId = WmsJwtAuthorities.enterpriseId(jwt);
            JobRunMapper mapper = session.getMapper(JobRunMapper.class);
            Map<String, Object> run = mapper.lockRunById(enterpriseId, warehouseId, jobId);
            if (run == null) {
                throw new JobRunException("RESOURCE_NOT_FOUND", "任务不存在");
            }
            JobRunService service = new JobRunService(session, Clock.systemUTC());
            Map<String, Object> result = new LinkedHashMap<>();
            if ("CLAIM".equalsIgnoreCase(action) || "TAKEOVER".equalsIgnoreCase(action)) {
                result.putAll(service.claim(enterpriseId, warehouseId, String.valueOf(run.get("job_type")),
                        jwt.getSubject(), Duration.ofSeconds(30)));
            } else {
                result.put("reclaimed", service.reclaimExpired(enterpriseId, warehouseId));
                result.put("action", "RECLAIM");
            }
            session.commit();
            Map<String, Object> after = mapper.lockRunById(enterpriseId, warehouseId, jobId);
            result.put("job", InventoryHttpJson.body(after));
            result.put("shards", InventoryHttpJson.rows(mapper.listShards(enterpriseId, warehouseId, jobId)));
            return result;
        }
    }

    @ExceptionHandler(WarehouseForbiddenException.class)
    ResponseEntity<Map<String, Object>> forbidden(WarehouseForbiddenException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("WAREHOUSE_FORBIDDEN", "无权访问该仓"));
    }

    @ExceptionHandler(JobRunException.class)
    ResponseEntity<Map<String, Object>> job(JobRunException error) {
        HttpStatus status = "RESOURCE_NOT_FOUND".equals(error.code()) ? HttpStatus.NOT_FOUND
                : error.code().contains("CONFLICT") || "STALE_FENCE".equals(error.code())
                        ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(error(error.code(), error.getMessage()));
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("requestId", UUID.randomUUID().toString());
        body.put("retryable", false);
        return body;
    }
}
