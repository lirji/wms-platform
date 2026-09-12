package com.lrj.wms.outbound.order;

import java.math.BigDecimal;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** OutboundWorkbenchController 的协议 DTO；不直接暴露数据库对象。 */
public final class OutboundWorkbenchRequests {
    private OutboundWorkbenchRequests() { }
    /** CreateRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record CreateRequest(
            @NotBlank @Size(max = 64) String allocationId,
            @Size(max = 64) String attemptId,
            @NotBlank @Size(max = 64) String ownerId,
            @Size(max = 64) String authorizationId,
            @NotNull @Size(min = 1, max = 200) List<@Valid OutboundLine> lines) { }
    /** AuthorizeRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record AuthorizeRequest(
            @Size(max = 64) String clientOperationId,
            @NotBlank @Size(max = 64) String attemptId,
            @NotBlank @Size(max = 64) String authorizationId,
            @NotBlank @Size(max = 128) String xid,
            @NotBlank @Size(max = 256) String tcTerminalEvidenceRef,
            @NotBlank @Size(max = 64) String participantSetHash) { }
    /** PlanPickRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record PlanPickRequest(
            @NotBlank @Size(max = 64) String orderLineId,
            @NotBlank @Size(max = 64) String sourceLocationId,
            @NotBlank @Size(max = 64) String stagingLocationId,
            @NotNull @Digits(integer = 14, fraction = 6) @DecimalMin(value = "0", inclusive = false) BigDecimal qty,
            @Size(max = 64) String clientOperationId) { }
    /** ClaimRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record ClaimRequest(
            @NotNull @Min(0) Long expectedVersion,
            @Size(max = 64) String clientOperationId) { }
    /** PickRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record PickRequest(
            @Size(min = 1, max = 64) String lotId,
            @Size(max = 64) String pickPartId,
            @Size(max = 64) String clientOperationId,
            @NotNull @Digits(integer = 14, fraction = 6) @DecimalMin(value = "0", inclusive = false) BigDecimal qty,
            @Valid com.lrj.wms.contract.messaging.SerialExecutionSelection serialExecution) {
        /** 数量与身份必须一起在协议边界验证，避免业务异常被误报为服务端失败。 */
        public PickRequest { if (serialExecution != null) serialExecution.requireQuantity(qty); }
        public PickRequest(String lotId,String pickPartId,String clientOperationId,BigDecimal qty) {this(lotId,pickPartId,clientOperationId,qty,null);}
    }
    /** PackRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record PackRequest(
            @NotBlank @Size(max = 64) String orderLineId,
            @Size(max = 64) String packageNo,
            @NotNull @Digits(integer = 14, fraction = 6) @DecimalMin(value = "0", inclusive = false) BigDecimal qty) { }
    /** ShipRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record ShipRequest(
            @Size(min = 1, max = 64) String stagingLocationId,
            @Size(min = 1, max = 64) String lotId,
            @Size(max = 64) String shipmentPartId,
            @NotBlank @Size(max = 64) String orderLineId,
            @Size(max = 64) String clientOperationId,
            @NotNull @Digits(integer = 14, fraction = 6) @DecimalMin(value = "0", inclusive = false) BigDecimal qty,
            @Valid com.lrj.wms.contract.messaging.SerialExecutionSelection serialExecution) {
        /** 协议边界验证数量与身份集合，避免到业务事务内才发现不一致。 */
        public ShipRequest { if(serialExecution!=null) serialExecution.requireQuantity(qty); }
        public ShipRequest(String stagingLocationId,String lotId,String shipmentPartId,String orderLineId,String clientOperationId,BigDecimal qty) {
            this(stagingLocationId,lotId,shipmentPartId,orderLineId,clientOperationId,qty,null);
        }
    }
    /** CancelRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record CancelRequest(
            @Digits(integer = 14, fraction = 6) @DecimalMin(value = "0", inclusive = false) BigDecimal qty,
            @Size(min = 1, max = 64) String sourceLocationId,
            @Size(min = 1, max = 64) String lotId,
            @NotBlank @Size(max = 64) String orderLineId,
            @Size(max = 64) String clientOperationId) { }
    /** OutboundLine：在数据库用例开始前校验类型、范围和必填项。 */
    public record OutboundLine(
            @NotBlank @Size(max = 64) String orderLineId,
            @NotBlank @Size(max = 64) String skuId,
            @NotNull @Digits(integer = 14, fraction = 6) @DecimalMin(value = "0", inclusive = false) BigDecimal qty,
            @Size(max = 32) String baseUnit) { }
}
