package com.lrj.wms.inventory.tcc;

import com.lrj.wms.contract.tcc.WarehouseTryRequest;
import com.lrj.wms.contract.tcc.WarehouseTryResult;
import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.security.WmsJwtAuthorities;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 内部Try只信任服务JWT，企业/仓/专用权限全部校验；不提供HTTP Confirm/Cancel。 */
@RestController
@ConditionalOnProperty(name="wms.tcc.rm.enabled",havingValue="true")
public class RuntimeTccController {
    private final RuntimeTccCoordinator coordinator;
    private final SeataRmDriver driver;
    public RuntimeTccController(RuntimeTccCoordinator coordinator,SeataRmDriver driver){this.coordinator=coordinator;this.driver=driver;}
    @PostMapping("/internal/wms/v1/warehouses/{warehouseId}/tcc/tries")
    public WarehouseTryResult tryReserve(@AuthenticationPrincipal Jwt jwt,@PathVariable String warehouseId,
            @RequestHeader("TX_XID") String xid,@RequestBody tools.jackson.databind.JsonNode body) {
        // 版本及代际不能接受浮点截断；Jackson默认的数值转换不作为契约版本校验。
        if(!body.isObject() || !body.path("schemaVersion").isIntegralNumber() || !body.path("schemaVersion").canConvertToInt()
                || body.path("schemaVersion").asInt()!=1 || !body.path("routeEpoch").isIntegralNumber() || !body.path("routeEpoch").canConvertToLong())
            throw new IllegalArgumentException("Try版本或路由代际必须是整数");
        for(String key:java.util.List.of("enterpriseId","warehouseId","ownerId","allocationId","attemptId","cellId"))
            if(!body.path(key).isString())throw new IllegalArgumentException("Try标识必须为字符串");
        var lines=body.path("lines");
        if(!lines.isArray() || lines.isEmpty() || lines.size()>200)throw new IllegalArgumentException("Try行数越界");
        for(var line:lines) {
            for(String key:java.util.List.of("orderLineId","skuId","sourceLocationId","lotId","baseUnit"))
                if(!line.path(key).isString())throw new IllegalArgumentException("Try行标识必须为字符串");
            if(!line.path("minRemainingDays").isIntegralNumber() || !line.path("minRemainingDays").canConvertToInt()
                    || !(line.path("qty").isNumber() || line.path("qty").isString()))throw new IllegalArgumentException("Try数量或效期类型无效");
        }
        WarehouseTryRequest request=com.lrj.wms.runtime.messaging.RuntimeMessage.JSON.treeToValue(body,WarehouseTryRequest.class);
        if(!"wms-fulfillment".equals(jwt.getSubject()) || !WmsJwtAuthorities.enterpriseId(jwt).equals(request.enterpriseId()))
            throw new org.springframework.security.access.AccessDeniedException("无权调用库存RM");
        WmsJwtAuthorities.requireScope(jwt,"inventory.tcc.try");WmsJwtAuthorities.requireWarehouse(jwt,warehouseId);
        if(!warehouseId.equals(request.warehouseId()))throw new org.springframework.security.access.AccessDeniedException("请求仓与路径不一致");
        driver.requireXid(xid);
        return coordinator.tryReserve(request,xid,driver::register);
    }
    @ExceptionHandler({com.lrj.wms.security.ScopeForbiddenException.class,com.lrj.wms.security.WarehouseForbiddenException.class})
    ResponseEntity<java.util.Map<String,String>> forbidden(RuntimeException error){return ResponseEntity.status(403).body(java.util.Map.of("code","TCC_SCOPE_FORBIDDEN"));}
    @ExceptionHandler({IllegalArgumentException.class,tools.jackson.core.JacksonException.class})
    ResponseEntity<java.util.Map<String,String>> invalid(RuntimeException error){return ResponseEntity.badRequest().body(java.util.Map.of("code","INVALID_TCC_REQUEST"));}
    @ExceptionHandler(InventoryException.class)
    ResponseEntity<java.util.Map<String,String>> domain(InventoryException error) {
        int status=switch(error.code()){case "TCC_REGISTRATION_UNKNOWN"->503;case "TCC_ADMISSION_REJECTED"->429;default->409;};
        return ResponseEntity.status(status).body(java.util.Map.of("code",error.code(),"message",error.getMessage()));
    }
}
