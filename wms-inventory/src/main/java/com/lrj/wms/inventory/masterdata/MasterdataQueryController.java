package com.lrj.wms.inventory.masterdata;

import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import com.lrj.wms.security.WarehouseForbiddenException;
import com.lrj.wms.security.WmsJwtAuthorities;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
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

/** 主数据查询；列表与按标识读取。写入走 MasterdataCommandController。 */
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

    /** 当前策略版本单位换算；商品必须属于令牌企业。 */
    @GetMapping("/skus/{skuId}/units")
    public Map<String, Object> skuUnits(@AuthenticationPrincipal Jwt jwt, @PathVariable String skuId) {
        String enterprise = WmsJwtAuthorities.enterpriseId(jwt);
        try (SqlSession session = sessions.openSession()) {
            MasterdataMapper mapper = session.getMapper(MasterdataMapper.class);
            if (mapper.countSku(enterprise, skuId) == 0) {
                throw new NoSuchElementException("商品不存在");
            }
            return page(mapper.listSkuUnits(enterprise, skuId));
        }
    }

    /** 仓级批次列表，越仓拒绝。 */
    @GetMapping("/warehouses/{warehouseId}/lots")
    public Map<String, Object> lots(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            return MasterdataHttp.page(session.getMapper(MasterdataMapper.class)
                    .listLots(WmsJwtAuthorities.enterpriseId(jwt), warehouseId));
        }
    }

    @GetMapping("/warehouses/{warehouseId}")
    public Map<String, Object> warehouse(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            return MasterdataHttp.row(new MasterdataCommandService(session, Clock.systemUTC())
                    .requireWarehouse(WmsJwtAuthorities.enterpriseId(jwt), warehouseId));
        }
    }

    @GetMapping("/warehouses/{warehouseId}/locations/{locationId}")
    public Map<String, Object> location(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String locationId) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            return MasterdataHttp.row(new MasterdataCommandService(session, Clock.systemUTC())
                    .requireLocation(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, locationId));
        }
    }

    @GetMapping("/warehouses/{warehouseId}/locations/{locationId}/gate")
    public Map<String, Object> gate(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String locationId) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            return MasterdataHttp.row(new MasterdataCommandService(session, Clock.systemUTC())
                    .requireGate(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, locationId));
        }
    }

    @GetMapping("/skus/{skuId}")
    public Map<String, Object> sku(@AuthenticationPrincipal Jwt jwt, @PathVariable String skuId) {
        try (SqlSession session = sessions.openSession()) {
            MasterdataCommandService service = new MasterdataCommandService(session, Clock.systemUTC());
            Map<String, Object> row = service.requireSku(WmsJwtAuthorities.enterpriseId(jwt), skuId);
            row.put("units", session.getMapper(MasterdataMapper.class)
                    .listSkuUnits(WmsJwtAuthorities.enterpriseId(jwt), skuId));
            return MasterdataHttp.row(row);
        }
    }

    @GetMapping("/warehouses/{warehouseId}/lots/{lotId}")
    public Map<String, Object> lot(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String lotId) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            return MasterdataHttp.row(new MasterdataCommandService(session, Clock.systemUTC())
                    .requireLot(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, lotId));
        }
    }

    @ExceptionHandler(WarehouseForbiddenException.class)
    ResponseEntity<Map<String, Object>> forbidden(WarehouseForbiddenException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(MasterdataHttp.error("WAREHOUSE_FORBIDDEN", "无权访问该仓"));
    }

    @ExceptionHandler(MasterdataException.class)
    ResponseEntity<Map<String, Object>> missingMasterdata(MasterdataException error) {
        return MasterdataHttp.statusOf(error);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, Object>> missing(NoSuchElementException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(MasterdataHttp.error("SKU_NOT_FOUND", "商品不存在"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> missingEnterprise(IllegalArgumentException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(MasterdataHttp.error("ENTERPRISE_SCOPE_MISSING", "令牌缺少企业范围"));
    }

    private static Map<String, Object> page(List<Map<String, Object>> items) {
        return MasterdataHttp.page(items);
    }
}
