package com.lrj.wms.inventory.masterdata;

import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import com.lrj.wms.security.WarehouseForbiddenException;
import com.lrj.wms.security.WmsJwtAuthorities;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.web.bind.annotation.RestController;

/** 主数据只读查询；数据来自数据库种子，页面不得写死。 */
@RestController
@RequestMapping("/api/wms/v1")
@ConditionalOnBean(SqlSessionFactory.class)
public class MasterdataQueryController {
    private final SqlSessionFactory sessions;

    public MasterdataQueryController(SqlSessionFactory sessions) {
        this.sessions = sessions;
    }

    /** 当前身份可见仓库列表。 */
    @GetMapping("/warehouses")
    public Map<String, Object> warehouses(@AuthenticationPrincipal Jwt jwt) {
        String enterprise = WmsJwtAuthorities.enterpriseId(jwt);
        Set<String> allowed = WmsJwtAuthorities.warehouses(jwt);
        try (SqlSession session = sessions.openSession()) {
            List<Map<String, Object>> items = session.getMapper(MasterdataMapper.class).listWarehouses(enterprise)
                    .stream().filter(row -> allowed.contains(String.valueOf(row.get("id")))).toList();
            return page(items);
        }
    }

    /** 库位列表，越仓拒绝。 */
    @GetMapping("/warehouses/{warehouseId}/locations")
    public Map<String, Object> locations(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            return page(session.getMapper(MasterdataMapper.class)
                    .listLocations(WmsJwtAuthorities.enterpriseId(jwt), warehouseId));
        }
    }

    /** 商品列表。 */
    @GetMapping("/skus")
    public Map<String, Object> skus(@AuthenticationPrincipal Jwt jwt) {
        try (SqlSession session = sessions.openSession()) {
            return page(session.getMapper(MasterdataMapper.class).listSkus(WmsJwtAuthorities.enterpriseId(jwt)));
        }
    }

    @ExceptionHandler(WarehouseForbiddenException.class)
    ResponseEntity<Map<String, Object>> forbidden(WarehouseForbiddenException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorBody("WAREHOUSE_FORBIDDEN", "无权访问该仓"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> missingEnterprise(IllegalArgumentException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorBody("ENTERPRISE_SCOPE_MISSING", "令牌缺少企业范围"));
    }

    private static Map<String, Object> errorBody(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("requestId", UUID.randomUUID().toString());
        body.put("retryable", false);
        return body;
    }

    private static Map<String, Object> page(List<Map<String, Object>> items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("limit", items.size());
        return body;
    }
}
