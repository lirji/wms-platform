package com.lrj.wms.fulfillment;

import com.lrj.wms.contract.messaging.*;
import com.lrj.wms.security.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 新公开序列路径默认关闭；旧消费者退出后启用，不改变既有数量请求的字段含义。 */
@RestController
@ConditionalOnProperty(name="wms.transfer.serial.enabled",havingValue="true")
@RequestMapping("/api/wms/v1")
public class SerialTransferController {
    private final SqlSessionFactory sessions;
    public SerialTransferController(SqlSessionFactory sessions,org.springframework.core.env.Environment environment) {
        if(!environment.getProperty("wms.messaging.enabled",Boolean.class,false)) throw new IllegalArgumentException("公开序列调拨必须开启可靠消息运行链路");
        this.sessions=sessions;
    }
    /** 源操作固定SN和原epoch；202表示已持久接收，不能当作已发出。 */
    @PostMapping("/transfers/{transferId}/serial-issues")
    public ResponseEntity<Map<String,Object>> issue(@AuthenticationPrincipal Jwt jwt,@PathVariable String transferId,
            @RequestHeader("Idempotency-Key") String operation,@Valid @RequestBody Issue body) {
        WmsJwtAuthorities.requireScope(jwt,"transfer.create");String e=WmsJwtAuthorities.enterpriseId(jwt);
        try(var session=sessions.openSession(false)) {
            var transfer=new TransferService(session,Clock.systemUTC()).get(e,transferId);
            WmsJwtAuthorities.requireWarehouse(jwt,(String)transfer.get("sourceWarehouseId"));
            var result=new SerialTransferService(session,Clock.systemUTC()).request(e,transferId,body.lineId(),operation,"ISSUE",body.postingContext(),body.selection(),body.qty(),null,null,jwt.getSubject());
            session.commit();return accepted(result);
        }
    }
    /** 目的选择必须来自该原调拨已发出的集合，原接收额度与命令绑定后不能换批。 */
    @PostMapping("/warehouses/{warehouseId}/serial-transfer-receipts")
    public ResponseEntity<Map<String,Object>> receive(@AuthenticationPrincipal Jwt jwt,@PathVariable String warehouseId,
            @RequestHeader("Idempotency-Key") String operation,@Valid @RequestBody Receipt body) {
        WmsJwtAuthorities.requireScope(jwt,"transfer.receive");WmsJwtAuthorities.requireWarehouse(jwt,warehouseId);String e=WmsJwtAuthorities.enterpriseId(jwt);
        if(!(body.tokenVersion() instanceof Integer || body.tokenVersion() instanceof Long)) throw new IllegalArgumentException("额度版本必须为整数");
        try(var session=sessions.openSession(false)) {
            var transfer=new TransferService(session,Clock.systemUTC()).get(e,body.transferId());
            if(!warehouseId.equals(transfer.get("targetWarehouseId"))) throw new WarehouseForbiddenException(warehouseId);
            var result=new SerialTransferService(session,Clock.systemUTC()).request(e,body.transferId(),body.lineId(),operation,"RECEIVE",body.postingContext(),body.selection(),body.qty(),body.authorizationId(),body.tokenVersion().longValue(),jwt.getSubject());
            session.commit();return accepted(result);
        }
    }
    /** 原两仓有读权限者可查看命令及完整SN集合，目的操作员无需获得源仓写权限。 */
    @GetMapping("/transfers/{transferId}/serial-commands/{commandId}")
    public Map<String,Object> get(@AuthenticationPrincipal Jwt jwt,@PathVariable String transferId,@PathVariable String commandId) {
        WmsJwtAuthorities.requireScope(jwt,"transfer.read");String e=WmsJwtAuthorities.enterpriseId(jwt);
        try(var session=sessions.openSession(false)) {
            var order=new TransferService(session,Clock.systemUTC()).get(e,transferId);
            if(!WmsJwtAuthorities.warehouses(jwt).contains(order.get("sourceWarehouseId")) && !WmsJwtAuthorities.warehouses(jwt).contains(order.get("targetWarehouseId"))) throw new WarehouseForbiddenException("transfer");
            var result=new SerialTransferService(session,Clock.systemUTC()).get(e,commandId);
            if(!transferId.equals(result.get("transferId"))) throw new TransferException("RESOURCE_NOT_FOUND","该单据不存在此命令");
            return result;
        }
    }
    public record Issue(@NotBlank @Size(max=64) String lineId,@NotNull @Valid StockPostingContext postingContext,
            @NotNull @Valid SerialExecutionSelection selection,@NotNull @Digits(integer=14,fraction=6) @DecimalMin(value="0",inclusive=false) BigDecimal qty) { }
    public record Receipt(@NotBlank @Size(max=64) String transferId,@NotBlank @Size(max=64) String lineId,
            @NotNull @Valid StockPostingContext postingContext,@NotNull @Valid SerialExecutionSelection selection,
            @NotNull @Digits(integer=14,fraction=6) @DecimalMin(value="0",inclusive=false) BigDecimal qty,
            @NotBlank @Size(max=64) String authorizationId,@NotNull @PositiveOrZero Number tokenVersion) { }
    private static ResponseEntity<Map<String,Object>> accepted(Map<String,Object> result) {return ResponseEntity.status("COMPLETE".equals(result.get("state"))?200:202).body(result);}
    @ExceptionHandler({ScopeForbiddenException.class,WarehouseForbiddenException.class})
    ResponseEntity<Map<String,Object>> forbidden() {return ResponseEntity.status(403).body(error("WAREHOUSE_FORBIDDEN"));}
    @ExceptionHandler({TransferException.class,IllegalArgumentException.class})
    ResponseEntity<Map<String,Object>> invalid(RuntimeException failure) {
        String code=failure instanceof TransferException transfer?transfer.code():"INVALID_TRANSFER_COMMAND";
        int status="RESOURCE_NOT_FOUND".equals(code)?404:code.contains("CONFLICT") || code.contains("ASSIGNED")?409:400;
        return ResponseEntity.status(status).body(error(code));
    }
    @ExceptionHandler(org.apache.ibatis.exceptions.PersistenceException.class)
    ResponseEntity<Map<String,Object>> database(Exception failure) {return new com.lrj.wms.runtime.web.RuntimeErrors().database(failure);}
    private static Map<String,Object> error(String code) {return Map.of("code",code,"message","当前范围、原身份或调拨状态不允许该操作","retryable",false);}
}
