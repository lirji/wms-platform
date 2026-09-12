package com.lrj.wms.inventory.query;

import com.lrj.wms.inventory.count.CountMapper;
import com.lrj.wms.inventory.count.CountService;
import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.jobs.JobRunMapper;
import com.lrj.wms.security.WarehouseForbiddenException;
import com.lrj.wms.security.WmsJwtAuthorities;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 任务与盘点只读查询，供控制台展示进度与冻结状态。 */
@RestController
@RequestMapping("/api/wms/v1")
@ConditionalOnBean(SqlSessionFactory.class)
public class OperationsQueryController {
    private final SqlSessionFactory sessions;

    public OperationsQueryController(SqlSessionFactory sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/jobs")
    public Map<String, Object> jobs(@AuthenticationPrincipal Jwt jwt, @RequestParam(name = "warehouseId") String warehouseId,
            @RequestParam(name = "limit", required = false) Integer limit) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            List<Map<String, Object>> runs = session.getMapper(JobRunMapper.class)
                    .listRuns(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, limit == null ? 50 : limit);
            return page(runs);
        }
    }

    @GetMapping("/jobs/{jobId}")
    public Map<String, Object> job(@AuthenticationPrincipal Jwt jwt, @PathVariable String jobId,
            @RequestParam(name = "warehouseId") String warehouseId) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            JobRunMapper mapper = session.getMapper(JobRunMapper.class);
            Map<String, Object> run = mapper.lockRunById(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, jobId);
            if (run == null) {
                throw new IllegalArgumentException("任务不存在");
            }
            Map<String, Object> body = row(run);
            body.put("shards", jsonValue(mapper.listShards(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, jobId)));
            return body;
        }
    }

    @GetMapping("/warehouses/{warehouseId}/count-plans")
    public Map<String, Object> counts(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @RequestParam(name = "limit", required = false) Integer limit) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            return page(session.getMapper(CountMapper.class).listPlans(WmsJwtAuthorities.enterpriseId(jwt), warehouseId,
                    limit == null ? 50 : limit));
        }
    }

    @GetMapping("/warehouses/{warehouseId}/count-plans/{countPlanId}")
    public Map<String, Object> count(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String countPlanId) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            return row(new CountService(session, Clock.systemUTC())
                    .get(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, countPlanId));
        }
    }

    @ExceptionHandler(WarehouseForbiddenException.class)
    ResponseEntity<Map<String, Object>> forbidden(WarehouseForbiddenException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorBody("WAREHOUSE_FORBIDDEN", "无权访问该仓"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> missing(IllegalArgumentException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody("RESOURCE_NOT_FOUND", error.getMessage()));
    }

    @ExceptionHandler(InventoryException.class)
    ResponseEntity<Map<String, Object>> inventory(InventoryException error) {
        return ResponseEntity.status(error.code().contains("NOT_FOUND") ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST)
                .body(errorBody(error.code(), error.getMessage()));
    }

    private static Map<String, Object> page(List<Map<String, Object>> items) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> item : items) {
            rows.add(row(item));
        }
        return Map.of("items", rows, "limit", rows.size());
    }

    private static Map<String, Object> row(Map<String, Object> source) {
        Map<String, Object> item = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            item.put(entry.getKey(), jsonValue(entry.getValue()));
        }
        return item;
    }

    private static Object jsonValue(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.atZone(ZoneId.systemDefault()).toInstant().toString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof List<?> list) {
            List<Object> items = new ArrayList<>();
            for (Object item : list) {
                items.add(item instanceof Map<?, ?> map ? row(cast(map)) : jsonValue(item));
            }
            return items;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
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
