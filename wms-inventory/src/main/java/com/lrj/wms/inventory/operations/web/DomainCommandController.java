package com.lrj.wms.inventory.operations.web;

import com.lrj.wms.inventory.inventory.domain.InventoryException;
import com.lrj.wms.inventory.operations.application.StockHoldService;
import com.lrj.wms.inventory.operations.application.WarehouseAdjustmentService;
import com.lrj.wms.inventory.operations.application.WarehouseMoveService;
import com.lrj.wms.security.authorization.ScopeForbiddenException;
import com.lrj.wms.security.authorization.WarehouseForbiddenException;
import com.lrj.wms.security.authorization.WmsJwtAuthorities;

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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Map;

/** 移库、库存限制与独立调整 HTTP。限制不复用盘点冻结。 */
@RestController
@RequestMapping("/api/wms/v1/warehouses/{warehouseId}")
@ConditionalOnBean(SqlSessionFactory.class)
public class DomainCommandController {
    private final SqlSessionFactory sessions;

    /** 显式接收 DomainCommandController 的协作对象或配置，保持本实例使用的依赖与创建入口一致。 */
    public DomainCommandController(SqlSessionFactory sessions) {
        this.sessions = sessions;
    }

    /** 处理 POST /moves 的协议绑定；业务决策沿用应用入口，避免 HTTP 层复制状态规则。 */
    @PostMapping("/moves")
    public ResponseEntity<Map<String, Object>> move(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String warehouseId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @jakarta.validation.Valid @RequestBody DomainCommandRequests.MoveRequest body) {
        WmsJwtAuthorities.requireScope(jwt, "stock.move");
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        String key = DomainHttp.requireMatchingKey(idempotencyKey, body.clientOperationId());
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> result =
                    new WarehouseMoveService(session, Clock.systemUTC())
                            .move(
                                    WmsJwtAuthorities.enterpriseId(jwt),
                                    warehouseId,
                                    key,
                                    jwt.getSubject(),
                                    body.sourceBalanceId(),
                                    body.targetLocationId(),
                                    DomainHttp.requireQty(body.qty(), "移库数量"),
                                    body.unit(),
                                    body.reason());
            session.commit();
            Map<String, Object> accepted =
                    DomainHttp.accepted(
                            warehouseId,
                            key,
                            "/api/wms/v1/warehouses/"
                                    + warehouseId
                                    + "/inventory/"
                                    + result.get("sourceBalanceId")
                                    + "/ledger",
                            "MOVED",
                            "APPLIED");
            accepted.putAll(DomainHttp.row(result));
            return ResponseEntity.accepted().body(accepted);
        }
    }

    /** 处理 POST /stock-holds 的协议绑定；业务决策沿用应用入口，避免 HTTP 层复制状态规则。 */
    @PostMapping("/stock-holds")
    public ResponseEntity<Map<String, Object>> hold(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String warehouseId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @jakarta.validation.Valid @RequestBody DomainCommandRequests.HoldRequest body) {
        WmsJwtAuthorities.requireScope(jwt, "stock.hold");
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        String key = DomainHttp.requireMatchingKey(idempotencyKey, body.clientOperationId());
        var scope = body.scope();
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> result =
                    new StockHoldService(session, Clock.systemUTC())
                            .create(
                                    WmsJwtAuthorities.enterpriseId(jwt),
                                    warehouseId,
                                    key,
                                    jwt.getSubject(),
                                    scope.balanceId(),
                                    DomainHttp.requireQty(
                                            firstNonNull(scope.qty(), body.qty()), "限制数量"),
                                    body.reason(),
                                    DomainHttp.jsonArray(body.evidenceRefs()));
            session.commit();
            Map<String, Object> accepted =
                    DomainHttp.accepted(
                            warehouseId,
                            key,
                            "/api/wms/v1/warehouses/"
                                    + warehouseId
                                    + "/inventory/"
                                    + result.get("balanceId")
                                    + "/ledger",
                            "HOLD_OPEN",
                            "APPLIED");
            accepted.putAll(DomainHttp.row(result));
            return ResponseEntity.accepted().body(accepted);
        }
    }

    /** 处理 POST /stock-holds/{holdId}/releases 的协议绑定；业务决策沿用应用入口，避免 HTTP 层复制状态规则。 */
    @PostMapping("/stock-holds/{holdId}/releases")
    public Map<String, Object> release(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String warehouseId,
            @PathVariable String holdId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @jakarta.validation.Valid @RequestBody DomainCommandRequests.ReleaseRequest body) {
        WmsJwtAuthorities.requireScope(jwt, "stock.releaseHold");
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        String key = DomainHttp.requireMatchingKey(idempotencyKey, body.clientOperationId());
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> result =
                    new StockHoldService(session, Clock.systemUTC())
                            .release(
                                    WmsJwtAuthorities.enterpriseId(jwt),
                                    warehouseId,
                                    holdId,
                                    key,
                                    jwt.getSubject(),
                                    body.reason(),
                                    DomainHttp.requireVersion(body.expectedVersion()));
            session.commit();
            return DomainHttp.row(result);
        }
    }

    /** 处理 POST /adjustments 的协议绑定；业务决策沿用应用入口，避免 HTTP 层复制状态规则。 */
    @PostMapping("/adjustments")
    public ResponseEntity<Map<String, Object>> createAdjustment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String warehouseId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @jakarta.validation.Valid @RequestBody
                    DomainCommandRequests.CreateAdjustmentRequest body) {
        WmsJwtAuthorities.requireScope(jwt, "adjustment.create");
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        if (body.serialActions() != null && !body.serialActions().isEmpty()) {
            throw new InventoryException("INVALID_ARGUMENT", "独立调整不处理序列号身份，请走盘点");
        }
        String key = DomainHttp.requireMatchingKey(idempotencyKey, body.clientOperationId());
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> created =
                    new WarehouseAdjustmentService(session, Clock.systemUTC())
                            .create(
                                    WmsJwtAuthorities.enterpriseId(jwt),
                                    warehouseId,
                                    key,
                                    jwt.getSubject(),
                                    body.balanceId(),
                                    DomainHttp.requireQty(body.deltaQty(), "调整增量"),
                                    body.reason(),
                                    body.countLineId(),
                                    DomainHttp.jsonArray(body.evidenceRefs()));
            session.commit();
            return ResponseEntity.status(HttpStatus.CREATED).body(DomainHttp.row(created));
        }
    }

    /** 处理 GET /adjustments 的协议绑定；业务决策沿用应用入口，避免 HTTP 层复制状态规则。 */
    @GetMapping("/adjustments")
    public Map<String, Object> listAdjustments(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String warehouseId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        WmsJwtAuthorities.requireScope(jwt, "adjustment.read");
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            return DomainHttp.page(
                    new WarehouseAdjustmentService(session, Clock.systemUTC())
                            .list(
                                    WmsJwtAuthorities.enterpriseId(jwt),
                                    warehouseId,
                                    cursor,
                                    limit == null ? 50 : limit));
        }
    }

    /** 处理 GET /adjustments/{adjustmentId} 的协议绑定；业务决策沿用应用入口，避免 HTTP 层复制状态规则。 */
    @GetMapping("/adjustments/{adjustmentId}")
    public Map<String, Object> getAdjustment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String warehouseId,
            @PathVariable String adjustmentId) {
        WmsJwtAuthorities.requireScope(jwt, "adjustment.read");
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            return DomainHttp.row(
                    new WarehouseAdjustmentService(session, Clock.systemUTC())
                            .get(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, adjustmentId));
        }
    }

    /** 处理 POST /adjustments/{adjustmentId}/approvals 的协议绑定；业务决策沿用应用入口，避免 HTTP 层复制状态规则。 */
    @PostMapping("/adjustments/{adjustmentId}/approvals")
    public Map<String, Object> approve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String warehouseId,
            @PathVariable String adjustmentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @jakarta.validation.Valid @RequestBody DomainCommandRequests.ApproveRequest body) {
        WmsJwtAuthorities.requireScope(jwt, "adjustment.approve");
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        DomainHttp.requireMatchingKey(idempotencyKey, body.clientOperationId());
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> result =
                    new WarehouseAdjustmentService(session, Clock.systemUTC())
                            .decide(
                                    WmsJwtAuthorities.enterpriseId(jwt),
                                    warehouseId,
                                    adjustmentId,
                                    jwt.getSubject(),
                                    body.decision(),
                                    body.reason(),
                                    DomainHttp.requireVersion(body.expectedVersion()));
            session.commit();
            return DomainHttp.row(result);
        }
    }

    /** 处理 POST /adjustments/{adjustmentId}/applications 的协议绑定；业务决策沿用应用入口，避免 HTTP 层复制状态规则。 */
    @PostMapping("/adjustments/{adjustmentId}/applications")
    public ResponseEntity<Map<String, Object>> apply(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String warehouseId,
            @PathVariable String adjustmentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @jakarta.validation.Valid @RequestBody DomainCommandRequests.ApplyRequest body) {
        WmsJwtAuthorities.requireScope(jwt, "adjustment.apply");
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        String key = DomainHttp.requireMatchingKey(idempotencyKey, body.clientOperationId());
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> result =
                    new WarehouseAdjustmentService(session, Clock.systemUTC())
                            .apply(
                                    WmsJwtAuthorities.enterpriseId(jwt),
                                    warehouseId,
                                    adjustmentId,
                                    key,
                                    jwt.getSubject(),
                                    DomainHttp.requireVersion(body.expectedVersion()));
            session.commit();
            Map<String, Object> accepted =
                    DomainHttp.accepted(
                            warehouseId,
                            key,
                            "/api/wms/v1/warehouses/"
                                    + warehouseId
                                    + "/adjustments/"
                                    + adjustmentId,
                            "ADJUST_APPLIED",
                            "APPLIED");
            accepted.putAll(DomainHttp.row(result));
            return ResponseEntity.accepted().body(accepted);
        }
    }

    @ExceptionHandler(ScopeForbiddenException.class)
    ResponseEntity<Map<String, Object>> scope(ScopeForbiddenException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(DomainHttp.error("SCOPE_FORBIDDEN", "缺少作业权限"));
    }

    @ExceptionHandler(WarehouseForbiddenException.class)
    ResponseEntity<Map<String, Object>> forbidden(WarehouseForbiddenException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(DomainHttp.error("WAREHOUSE_FORBIDDEN", "无权访问该仓"));
    }

    @ExceptionHandler(InventoryException.class)
    ResponseEntity<Map<String, Object>> inventory(InventoryException error) {
        return DomainHttp.statusOf(error);
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }
}
