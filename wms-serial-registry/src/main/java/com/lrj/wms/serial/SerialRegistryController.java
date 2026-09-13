package com.lrj.wms.serial;

import com.lrj.wms.runtime.observability.RequestCorrelationFilter;
import com.lrj.wms.security.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.Map;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 登记仅接受已配置的服务主体+scope+企业仓范围，普通业务用户不能直接制造授权。 */
@RestController
@Conditional(OnSerialJdbcConfigured.class)
@RequestMapping("/internal/wms/v1/serial-identities")
public final class SerialRegistryController {
    private final SqlSessionFactory sessions;
    private final SerialAccessProperties access;
    private final SerialCommandService commands;

    public SerialRegistryController(SqlSessionFactory sessions, SerialAccessProperties access) {
        this.sessions = sessions; this.access = access;
        this.commands = new SerialCommandService(sessions, Clock.systemUTC());
    }

    /** 只认领全局身份，尚未赋予本地库存执行权。 */
    @PostMapping("/claims")
    public Map<String, Object> claim(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String command,
            @RequestHeader("X-Wms-Enterprise-Id") String expectedEnterprise, @Valid @RequestBody IdentityCommand body) {
        require(jwt, "serial.registry.write", body.warehouseId(), expectedEnterprise);
        String enterprise = WmsJwtAuthorities.enterpriseId(jwt);
        return commands.execute(enterprise, body.warehouseId(), command, jwt.getSubject(), "CLAIM", body,
                service -> service.claim(enterprise, body.skuId(), body.serial(), body.warehouseId(), body.operationId()));
    }

    /** 激活必须对应原收货操作，质量仍由库存HOLD桶及质检决定。 */
    @PostMapping("/activations")
    public Map<String, Object> activate(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String command,
            @RequestHeader("X-Wms-Enterprise-Id") String expectedEnterprise, @Valid @RequestBody IdentityCommand body) {
        require(jwt, "serial.registry.write", body.warehouseId(), expectedEnterprise);
        String enterprise = WmsJwtAuthorities.enterpriseId(jwt);
        return commands.execute(enterprise, body.warehouseId(), command, jwt.getSubject(), "ACTIVATE", body,
                service -> service.activate(enterprise, body.skuId(), body.serial(), body.warehouseId(), body.operationId()));
    }

    /** 查询返回实时登记；当前归属仓不在令牌范围时不能泄漏原操作引用。 */
    @GetMapping("")
    public Map<String, Object> get(@AuthenticationPrincipal Jwt jwt, @RequestHeader("X-Wms-Enterprise-Id") String expectedEnterprise,
            @RequestParam String skuId, @RequestParam String serial) {
        require(jwt, "serial.registry.read", null, expectedEnterprise);
        if (skuId.isBlank() || skuId.length() > 64 || serial.isBlank() || serial.length() > 128) {
            throw new SerialRegistryException("INVALID_ARGUMENT", "序列号查询参数不合法");
        }
        try (var session = sessions.openSession()) {
            var result = new SerialRegistryService(session, Clock.systemUTC()).get(WmsJwtAuthorities.enterpriseId(jwt), skuId, serial);
            WmsJwtAuthorities.requireWarehouse(jwt, String.valueOf(result.get("ownerWarehouseId")));
            return result;
        }
    }

    /** 仅受信库存主体登记已提交发运事实，独立历史证明不等于当前库存授权。 */
    @PostMapping("/shipments")
    public Map<String,Object> ship(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String command,
            @RequestHeader("X-Wms-Enterprise-Id") String enterprise,@Valid @RequestBody ShipmentCommand body) {
        require(jwt,"serial.registry.write",body.warehouseId(),enterprise);
        return commands.execute(enterprise,body.warehouseId(),command,jwt.getSubject(),"SHIP",body,
                service -> service.ship(enterprise,body.skuId(),body.serial(),body.warehouseId(),body.factRef(),body.expectedEpoch().longValue()));
    }

    /** 失踪与盘盈是有原事实引用的登记动作，复用本地幂等审计事务。 */
    @PostMapping("/missing")
    public Map<String,Object> missing(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String command,
            @RequestHeader("X-Wms-Enterprise-Id") String enterprise, @Valid @RequestBody MissingCommand body) {
        require(jwt,"serial.registry.write",body.warehouseId(),enterprise);
        return commands.execute(enterprise,body.warehouseId(),command,jwt.getSubject(),"MISSING",body,
                service -> service.markMissing(enterprise,body.skuId(),body.serial(),body.warehouseId(),body.factRef(),body.expectedEpoch()));
    }
    @PostMapping("/found-claims")
    public Map<String,Object> claimFound(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String command,
            @RequestHeader("X-Wms-Enterprise-Id") String enterprise, @Valid @RequestBody IdentityCommand body) {
        require(jwt,"serial.registry.write",body.warehouseId(),enterprise);
        return commands.execute(enterprise,body.warehouseId(),command,jwt.getSubject(),"FOUND_CLAIM",body,
                service -> service.claimFound(enterprise,body.skuId(),body.serial(),body.warehouseId(),body.operationId()));
    }
    @PostMapping("/found-activations")
    public Map<String,Object> activateFound(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String command,
            @RequestHeader("X-Wms-Enterprise-Id") String enterprise, @Valid @RequestBody IdentityCommand body) {
        require(jwt,"serial.registry.write",body.warehouseId(),enterprise);
        return commands.execute(enterprise,body.warehouseId(),command,jwt.getSubject(),"FOUND_ACTIVATE",body,
                service -> service.activateFound(enterprise,body.skuId(),body.serial(),body.warehouseId(),body.operationId()));
    }
    /** 准备转移需要源目的两仓权限；实际释放仍只能由源仓主体确认。 */
    @PostMapping("/transfer-preparations")
    public Map<String,Object> prepareTransfer(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String command,
            @RequestHeader("X-Wms-Enterprise-Id") String enterprise,
            @RequestHeader(value="X-Wms-Serial-Prepare-Proof",required=false) String proofVersion,@Valid @RequestBody PrepareTransferCommand body) {
        require(jwt,"serial.registry.write",body.warehouseId(),enterprise);
        WmsJwtAuthorities.requireWarehouse(jwt,body.targetWarehouseId());
        if(proofVersion!=null && !"1".equals(proofVersion)) throw new SerialRegistryException("INVALID_PROOF_VERSION","不支持的准备事实版本");
        return commands.execute(enterprise,body.warehouseId(),command,jwt.getSubject(),"TRANSFER_PREPARE",body,
                service -> proofVersion==null?service.prepareTransfer(enterprise,body.skuId(),body.serial(),body.warehouseId(),body.targetWarehouseId(),body.transferId(),body.expectedEpoch(),body.operationId())
                        :service.prepareTransferWithProof(enterprise,body.skuId(),body.serial(),body.warehouseId(),body.targetWarehouseId(),body.transferId(),body.expectedEpoch(),body.operationId()));
    }
    @PostMapping("/source-releases")
    public Map<String,Object> sourceRelease(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String command,
            @RequestHeader("X-Wms-Enterprise-Id") String enterprise,
            @RequestHeader(value="X-Wms-Serial-Release-Proof",required=false) String proofVersion, @Valid @RequestBody TransferFactCommand body) {
        require(jwt,"serial.registry.write",body.warehouseId(),enterprise);
        if(proofVersion!=null && !"1".equals(proofVersion)) throw new SerialRegistryException("INVALID_PROOF_VERSION","不支持的源释放凭证版本");
        var result=commands.execute(enterprise,body.warehouseId(),command,jwt.getSubject(),"SOURCE_RELEASE",body,service -> {
            var transfer=service.getTransfer(enterprise,body.skuId(),body.serial(),body.transferId());
            if(!body.warehouseId().equals(transfer.get("sourceWarehouseId"))) throw new ScopeForbiddenException("serial.registry.write");
            return service.observeSourceRelease(enterprise,body.skuId(),body.serial(),body.transferId(),body.factRef(),body.expectedEpoch());
        });
        // 旧客户端的严格响应结构保持原样；新客户端显式请求历史证明，业务命令身份不随表示变化。
        if(proofVersion==null) result.remove("sourceRelease");
        return result;
    }
    @PostMapping("/destination-receivings")
    public Map<String,Object> startReceiving(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String command,
            @RequestHeader("X-Wms-Enterprise-Id") String enterprise, @Valid @RequestBody TransferFactCommand body) {
        require(jwt,"serial.registry.write",body.warehouseId(),enterprise);
        return commands.execute(enterprise,body.warehouseId(),command,jwt.getSubject(),"DESTINATION_RECEIVING",body,
                service -> service.startReceiving(enterprise,body.skuId(),body.serial(),body.transferId(),body.warehouseId(),body.factRef(),body.expectedEpoch()));
    }
    @PostMapping("/destination-confirmations")
    public Map<String,Object> confirmDestination(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String command,
            @RequestHeader("X-Wms-Enterprise-Id") String enterprise, @Valid @RequestBody ConfirmTransferCommand body) {
        require(jwt,"serial.registry.write",body.warehouseId(),enterprise);
        return commands.execute(enterprise,body.warehouseId(),command,jwt.getSubject(),"DESTINATION_CONFIRM",body,
                service -> service.confirmDestination(enterprise,body.skuId(),body.serial(),body.transferId(),body.warehouseId(),body.factRef()));
    }
    /** 仅源或目的仓可读指定转移，不依据当前owner仓拒绝合法目的恢复查询。 */
    @GetMapping("/transfers/{transferId}")
    public Map<String,Object> getTransfer(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Wms-Enterprise-Id") String enterprise,
            @PathVariable String transferId,@RequestParam String warehouseId,@RequestParam String skuId,@RequestParam String serial) {
        require(jwt,"serial.registry.read",warehouseId,enterprise);
        if(transferId.isBlank() || transferId.length()>64 || skuId.isBlank() || skuId.length()>64 || serial.isBlank() || serial.length()>128)
            throw new SerialRegistryException("INVALID_ARGUMENT","转移查询参数不合法");
        try(var session=sessions.openSession()) {
            var result=new SerialRegistryService(session,Clock.systemUTC()).getTransfer(enterprise,skuId,serial,transferId);
            if(!warehouseId.equals(result.get("sourceWarehouseId")) && !warehouseId.equals(result.get("targetWarehouseId")))
                throw new ScopeForbiddenException("serial.registry.read");
            return result;
        }
    }
    /** factRef是已发生的库存事实，epoch防止旧归属事件覆盖现授权。 */
    public record ShipmentCommand(@NotBlank @Size(max=64) String warehouseId,@NotBlank @Size(max=64) String skuId,
            @NotBlank @Size(max=128) String serial,@NotBlank @Size(max=64) String factRef,
            @jakarta.validation.constraints.NotNull Number expectedEpoch) {
        /** 保留JSON数值类型再校验，禁止小数被Long反序列化截断后消费有效归属。 */
        public ShipmentCommand {
            if(!(expectedEpoch instanceof Integer || expectedEpoch instanceof Long) || expectedEpoch.longValue()<0)
                throw new IllegalArgumentException("发运归属代际必须是非负整数");
            expectedEpoch=expectedEpoch.longValue();
        }
    }
    public record MissingCommand(@NotBlank @Size(max=64) String warehouseId,@NotBlank @Size(max=64) String skuId,
            @NotBlank @Size(max=128) String serial,@NotBlank @Size(max=64) String factRef,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) Long expectedEpoch) { }
    public record PrepareTransferCommand(@NotBlank @Size(max=64) String warehouseId,@NotBlank @Size(max=64) String targetWarehouseId,
            @NotBlank @Size(max=64) String skuId,@NotBlank @Size(max=128) String serial,@NotBlank @Size(max=64) String transferId,
            @NotBlank @Size(max=64) String operationId,@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) Long expectedEpoch) { }
    public record TransferFactCommand(@NotBlank @Size(max=64) String warehouseId,@NotBlank @Size(max=64) String skuId,
            @NotBlank @Size(max=128) String serial,@NotBlank @Size(max=64) String transferId,@NotBlank @Size(max=64) String factRef,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) Long expectedEpoch) { }
    public record ConfirmTransferCommand(@NotBlank @Size(max=64) String warehouseId,@NotBlank @Size(max=64) String skuId,
            @NotBlank @Size(max=128) String serial,@NotBlank @Size(max=64) String transferId,@NotBlank @Size(max=64) String factRef) { }

    private void require(Jwt jwt, String scope, String warehouse, String expectedEnterprise) {
        // 防止多租户调用方误用另一企业的服务令牌，在产生登记写入之前拒绝范围不一致。
        if (!expectedEnterprise.equals(WmsJwtAuthorities.enterpriseId(jwt))) throw new ScopeForbiddenException(scope);
        if (!access.allowedSubjects().contains(jwt.getSubject())) throw new ScopeForbiddenException(scope);
        WmsJwtAuthorities.requireScope(jwt, scope);
        if (warehouse != null) WmsJwtAuthorities.requireWarehouse(jwt, warehouse);
    }

    /** operationId是原库存收货引用；HTTP Idempotency-Key是本次具体登记动作的独立命令身份。 */
    public record IdentityCommand(@NotBlank @Size(max = 64) String warehouseId,
            @NotBlank @Size(max = 64) String skuId, @NotBlank @Size(max = 128) String serial,
            @NotBlank @Size(max = 64) String operationId) { }

    @ExceptionHandler(SerialRegistryException.class)
    ResponseEntity<Map<String, Object>> conflict(SerialRegistryException failure) {
        int status = failure.code().equals("SERIAL_NOT_FOUND") ? 404 : failure.code().startsWith("INVALID_") ? 400 : 409;
        return ResponseEntity.status(status).body(Map.of("code", failure.code(), "message", failure.getMessage(),
                "retryable", false, "requestId", RequestCorrelationFilter.currentId()));
    }
    @ExceptionHandler({ScopeForbiddenException.class, WarehouseForbiddenException.class})
    ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(403).body(Map.of("code", "SERIAL_ACCESS_FORBIDDEN", "message", "缺少登记服务主体、权限或仓范围",
                "retryable", false, "requestId", RequestCorrelationFilter.currentId()));
    }
    @ExceptionHandler(org.apache.ibatis.exceptions.PersistenceException.class)
    ResponseEntity<Map<String, Object>> database(Exception failure) { return new com.lrj.wms.runtime.web.RuntimeErrors().database(failure); }
}
