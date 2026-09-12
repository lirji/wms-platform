package com.lrj.wms.inventory.count;

import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.security.WarehouseForbiddenException;
import com.lrj.wms.security.WmsJwtAuthorities;
import java.time.Clock;
import java.util.ArrayList;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 盘点写命令。排空后才能冻结，未审批不能调整。 */
@RestController
@RequestMapping("/api/wms/v1/warehouses/{warehouseId}")
@ConditionalOnBean(SqlSessionFactory.class)
public class CountCommandController {
    private final SqlSessionFactory sessions;

    public CountCommandController(SqlSessionFactory sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/count-plans")
    public ResponseEntity<Map<String, Object>> create(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String warehouseId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @jakarta.validation.Valid @RequestBody CountCommandRequests.CreateRequest body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> created = new CountService(session, Clock.systemUTC()).create(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId,
                    firstNonBlank(body.planId(), body.countPlanId(), idempotencyKey),
                    firstNonBlank(body.reason(), "CYCLE"), body.locationIds());
            session.commit();
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        }
    }

    @PostMapping("/count-plans/{countPlanId}/freeze-requests")
    public Map<String, Object> freeze(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String countPlanId, @jakarta.validation.Valid @RequestBody(required = false) CountCommandRequests.FreezeRequest body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        String phase = body == null ? null : body.phase();
        try (SqlSession session = sessions.openSession(false)) {
            CountService service = new CountService(session, Clock.systemUTC());
            String enterpriseId = WmsJwtAuthorities.enterpriseId(jwt);
            Map<String, Object> plan = service.get(enterpriseId, warehouseId, countPlanId);
            String status = String.valueOf(plan.get("status"));
            Map<String, Object> result;
            if ("QUIESCE".equalsIgnoreCase(phase) || CountService.DRAFT.equals(status)) {
                result = service.startQuiescing(enterpriseId, warehouseId, countPlanId);
            } else {
                result = service.freeze(enterpriseId, warehouseId, countPlanId);
            }
            session.commit();
            return result;
        }
    }

    @PostMapping("/count-plans/{countPlanId}/observations")
    public Map<String, Object> observe(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String countPlanId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @jakarta.validation.Valid @RequestBody CountCommandRequests.ObserveRequest body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession(false)) {
            var service=new CountService(session,Clock.systemUTC());
            String observation=com.lrj.wms.runtime.command.CommandKeys.resolve(idempotencyKey,body.observationId());
            Map<String,Object> result=body.serialObservation()==null
                    ? service.observe(WmsJwtAuthorities.enterpriseId(jwt),warehouseId,countPlanId,body.lineId(),observation,
                        String.valueOf(body.qty()),jwt.getSubject(),intValue(body.roundNo(),1))
                    : service.observeIdentities(WmsJwtAuthorities.enterpriseId(jwt),warehouseId,countPlanId,body.lineId(),observation,
                        String.valueOf(body.qty()),jwt.getSubject(),intValue(body.roundNo(),1),body.serialObservation().serialIds());
            session.commit();
            return result;
        }
    }

    @PostMapping("/count-plans/{countPlanId}/reviews")
    public Map<String, Object> review(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String countPlanId) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> result = new CountService(session, Clock.systemUTC())
                    .submitReview(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, countPlanId);
            session.commit();
            return result;
        }
    }

    @PostMapping("/count-plans/{countPlanId}/approvals")
    public Map<String, Object> approve(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String countPlanId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @jakarta.validation.Valid @RequestBody(required = false) CountCommandRequests.ApproveRequest body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> result = new CountService(session, Clock.systemUTC()).approve(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, countPlanId,
                    firstNonBlank(body == null ? null : body.approvalId(), idempotencyKey), jwt.getSubject());
            session.commit();
            return result;
        }
    }

    @PostMapping("/count-plans/{countPlanId}/applications")
    public ResponseEntity<Map<String, Object>> apply(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String countPlanId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @jakarta.validation.Valid @RequestBody CountCommandRequests.ApplyRequest body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> result = new CountService(session, Clock.systemUTC()).applyLine(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, countPlanId, body.lineId(),
                    com.lrj.wms.runtime.command.CommandKeys.resolve(idempotencyKey, body.clientOperationId()), jwt.getSubject());
            session.commit();
            return ResponseEntity.accepted().body(result);
        }
    }

    @ExceptionHandler(WarehouseForbiddenException.class)
    ResponseEntity<Map<String, Object>> forbidden(WarehouseForbiddenException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("WAREHOUSE_FORBIDDEN", "无权访问该仓"));
    }

    @ExceptionHandler(InventoryException.class)
    ResponseEntity<Map<String, Object>> inventory(InventoryException error) {
        HttpStatus status = error.code().contains("NOT_FOUND") ? HttpStatus.NOT_FOUND
                : error.code().contains("CONFLICT") || "COUNT_DRAIN_PENDING".equals(error.code())
                        ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(error(error.code(), error.getMessage()));
    }





    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return UUID.randomUUID().toString();
    }

    private static int intValue(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("requestId", com.lrj.wms.runtime.observability.RequestCorrelationFilter.currentId());
        body.put("retryable", false);
        return body;
    }
}
