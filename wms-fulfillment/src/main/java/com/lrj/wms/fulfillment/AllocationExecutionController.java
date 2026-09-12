package com.lrj.wms.fulfillment;

import com.lrj.wms.contract.tcc.WarehouseTryRequest;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import com.lrj.wms.security.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/** 显式提交固定桶执行计划；HTTP202只表示命令落库，不承诺已占库或已全局提交。 */
@RestController
@ConditionalOnProperty(name="wms.fulfillment.execution.enabled",havingValue="true")
public class AllocationExecutionController {
    private final AllocationExecutionService service;
    private final WarehouseTryHttpClient warehouse;
    private final Set<String> enterprises;
    public AllocationExecutionController(AllocationExecutionService service,WarehouseTryHttpClient warehouse,org.springframework.core.env.Environment env) {
        this.service=service;this.warehouse=warehouse;
        this.enterprises=new HashSet<>(Arrays.stream(env.getRequiredProperty("wms.fulfillment.execution.enterprises").split(",")).map(String::trim).toList());
    }
    @PostMapping("/api/wms/v1/fulfillments/{fulfillmentId}/attempts/{attemptId}/executions")
    public ResponseEntity<Map<String,Object>> execute(@AuthenticationPrincipal Jwt jwt,@PathVariable String fulfillmentId,@PathVariable String attemptId,
            @RequestHeader("Idempotency-Key") String key,@RequestBody JsonNode body) {
        WmsJwtAuthorities.requireScope(jwt,"fulfillment.execute");
        String enterprise=WmsJwtAuthorities.enterpriseId(jwt);
        if(!enterprises.contains(enterprise)) throw AllocationExecutionService.error("EXECUTION_TENANT_NOT_CONFIGURED");
        if(!body.isObject()||body.properties().stream().anyMatch(e->!Set.of("clientOperationId","warehouses").contains(e.getKey()))
                ||!body.path("warehouses").isArray()||body.path("warehouses").isEmpty()||body.path("warehouses").size()>200) throw new IllegalArgumentException();
        var operation=body.get("clientOperationId");
        if(operation!=null&&!operation.isNull()&&!operation.isString()) throw new IllegalArgumentException();
        String command=com.lrj.wms.runtime.command.CommandKeys.resolve(key,operation==null||operation.isNull()?null:operation.asString());
        List<WarehouseTryRequest> requests=new ArrayList<>();
        for(var node:body.path("warehouses")) {
            // 数值在JSON树层拒绝小数、溢出及字符串，防止Jackson先截断再经过Java构造器。
            if(!node.isObject()||node.properties().stream().anyMatch(e->!Set.of("schemaVersion","enterpriseId","warehouseId","ownerId","allocationId","attemptId","cellId","routeEpoch","lines").contains(e.getKey()))||!node.path("schemaVersion").isIntegralNumber()||!node.path("schemaVersion").canConvertToInt()||node.path("schemaVersion").asInt()!=1
                    ||!node.path("routeEpoch").isIntegralNumber()||!node.path("routeEpoch").canConvertToLong()||!node.path("lines").isArray()
                    ||node.path("lines").isEmpty()||node.path("lines").size()>200) throw new IllegalArgumentException();
            for(String field:List.of("enterpriseId","warehouseId","ownerId","allocationId","attemptId","cellId")) if(!node.path(field).isString()) throw new IllegalArgumentException();
            for(var line:node.path("lines")) {
                if(!line.isObject()||line.properties().stream().anyMatch(e->!Set.of("orderLineId","skuId","sourceLocationId","lotId","qty","baseUnit","minRemainingDays").contains(e.getKey()))||!line.path("minRemainingDays").isIntegralNumber()||!line.path("minRemainingDays").canConvertToInt()) throw new IllegalArgumentException();
                for(String field:List.of("orderLineId","skuId","sourceLocationId","lotId","baseUnit")) if(!line.path(field).isString()) throw new IllegalArgumentException();
                if(!line.path("qty").isString()&&!line.path("qty").isNumber()) throw new IllegalArgumentException();
            }
            var request=RuntimeMessage.JSON.treeToValue(node,WarehouseTryRequest.class);
            WmsJwtAuthorities.requireWarehouse(jwt,request.warehouseId());warehouse.requireCell(request.cellId());requests.add(request);
        }
        var result=service.submit(enterprise,fulfillmentId,attemptId,command,jwt.getSubject(),requests);
        result.put("attemptId",attemptId);result.put("statusUrl","/api/wms/v1/fulfillments/"+fulfillmentId);
        return ResponseEntity.accepted().body(HttpJson.row(result));
    }
    @ExceptionHandler(FulfillmentException.class)
    ResponseEntity<Map<String,Object>> domain(FulfillmentException error) {return ResponseEntity.status(409).body(HttpJson.error(error.code(),error.getMessage()));}
    @ExceptionHandler({ScopeForbiddenException.class,WarehouseForbiddenException.class})
    ResponseEntity<Map<String,Object>> forbidden(RuntimeException error) {return ResponseEntity.status(403).body(HttpJson.error("EXECUTION_FORBIDDEN","无权执行该范围"));}
    @ExceptionHandler({IllegalArgumentException.class,tools.jackson.core.JacksonException.class})
    ResponseEntity<Map<String,Object>> invalid(RuntimeException error) {return ResponseEntity.badRequest().body(HttpJson.error("INVALID_EXECUTION_REQUEST","执行请求格式无效"));}
}
