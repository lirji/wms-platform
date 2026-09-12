package com.lrj.wms.fulfillment;

import java.math.BigDecimal;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** 协议请求模型，约束在开启数据库事务前验证。 */
public final class FulfillmentWorkbenchRequests {
    private FulfillmentWorkbenchRequests() { }
    /** CreateFulfillmentRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record CreateFulfillmentRequest(
            @NotBlank @Size(max = 64) String sourceSystem,
            @NotBlank @Size(max = 64) String sourceOrderNo,
            @Size(max = 64) String digest,
            @NotNull @Size(min = 1, max = 200) List<@Valid FulfillmentLine> lines,
            @Min(0) Long strategyVersion,
            @Size(min = 1, max = 64) String ownerId) { }
    /** CancelFulfillmentRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record CancelFulfillmentRequest(
            @Size(max = 64) String clientOperationId,
            @Size(max = 128) String reason,
            @Min(0) Long expectedVersion) { }
    /** PrepareAttemptRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record PrepareAttemptRequest(
            @Size(max = 64) String clientOperationId,
            @Size(max = 64) String deadline,
            @Size(max = 200) List<@NotBlank @Size(max = 64) String> warehouses,
            @NotNull @Size(min = 1, max = 200) List<@Valid AttemptLine> lines) { }
    /** IssueTransferRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record IssueTransferRequest(
            @Size(max = 64) String lineId,
            @Size(max = 64) String transferLineId,
            @Size(max = 64) String clientOperationId,
            @NotNull @Digits(integer = 14, fraction = 6) @DecimalMin(value = "0", inclusive = false) BigDecimal qty) { }
    /** AuthorizeTransferReceiptRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record AuthorizeTransferReceiptRequest(
            @Size(max = 64) String lineId,
            @Size(max = 64) String transferLineId,
            @Size(max = 64) String targetClientOperationId,
            @Size(max = 64) String clientOperationId,
            @Digits(integer = 14, fraction = 6) @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
            @Digits(integer = 14, fraction = 6) @DecimalMin(value = "0", inclusive = false) BigDecimal qty) { }
    /** ReceiveTransferRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record ReceiveTransferRequest(
            @NotBlank @Size(max = 64) String transferId,
            @Size(max = 64) String lineId,
            @Size(max = 64) String sourceLineRef,
            @Size(max = 64) String clientOperationId,
            @NotBlank @Size(max = 64) String authorizationId,
            @NotNull @Min(0) Long tokenVersion,
            @NotNull @Digits(integer = 14, fraction = 6) @DecimalMin(value = "0", inclusive = false) BigDecimal qty,
            @Size(max = 64) String targetLotId) { }
    /** ConfirmTransferLossRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record ConfirmTransferLossRequest(
            @Size(max = 64) String lineId,
            @Size(max = 64) String transferLineId,
            @Size(max = 64) String clientOperationId,
            @NotNull @Digits(integer = 14, fraction = 6) @DecimalMin(value = "0", inclusive = false) BigDecimal qty) { }
    /** CreateTransferRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record CreateTransferRequest(
            @Size(max = 64) String transferId,
            @NotBlank @Size(max = 64) String sourceWarehouseId,
            @NotBlank @Size(max = 64) String targetWarehouseId,
            @NotNull @Size(min = 1, max = 200) List<@Valid TransferLine> lines) { }
    /** FulfillmentLine：在数据库用例开始前校验类型、范围和必填项。 */
    public record FulfillmentLine(
            @NotBlank @Size(max = 64) @com.fasterxml.jackson.annotation.JsonAlias("lineId") String sourceLineId,
            @NotBlank @Size(max = 64) String skuId,
            @NotNull @Digits(integer = 14, fraction = 6) @DecimalMin(value = "0", inclusive = false) @com.fasterxml.jackson.annotation.JsonAlias("qty") BigDecimal requestedQty,
            @NotBlank @Size(max = 32) @com.fasterxml.jackson.annotation.JsonAlias("unit") String baseUnit) {
        /** 显式映射现有应用行模型，保持十进制精度。 */
        public java.util.Map<String, Object> toModel() {
            java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
            values.put("sourceLineId", sourceLineId);
            values.put("skuId", skuId);
            values.put("requestedQty", requestedQty);
            values.put("baseUnit", baseUnit);
            return values;
        }
    }
    /** AttemptLine：在数据库用例开始前校验类型、范围和必填项。 */
    public record AttemptLine(
            @NotBlank @Size(max = 64) @com.fasterxml.jackson.annotation.JsonAlias("sourceLineId") String orderLineId,
            @NotBlank @Size(max = 64) String skuId,
            @NotBlank @Size(max = 64) String warehouseId,
            @NotNull @Digits(integer = 14, fraction = 6) @DecimalMin(value = "0", inclusive = false) BigDecimal qty,
            @NotBlank @Size(max = 32) @com.fasterxml.jackson.annotation.JsonAlias("unit") String baseUnit) {
        /** 显式映射现有应用行模型，保持十进制精度。 */
        public java.util.Map<String, Object> toModel() {
            java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
            values.put("orderLineId", orderLineId);
            values.put("skuId", skuId);
            values.put("warehouseId", warehouseId);
            values.put("qty", qty);
            values.put("baseUnit", baseUnit);
            return values;
        }
    }
    /** TransferLine：在数据库用例开始前校验类型、范围和必填项。 */
    public record TransferLine(
            @NotBlank @Size(max = 64) @com.fasterxml.jackson.annotation.JsonAlias("sourceLineId") String lineId,
            @NotBlank @Size(max = 64) String skuId,
            @NotNull @Digits(integer = 14, fraction = 6) @DecimalMin(value = "0", inclusive = false) @com.fasterxml.jackson.annotation.JsonAlias("qty") BigDecimal plannedQty,
            @Size(max = 64) String businessLotKey,
            @Size(max = 64) String sourceLotId) {
        /** 显式映射现有应用行模型，保持十进制精度。 */
        public java.util.Map<String, Object> toModel() {
            java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
            values.put("lineId", lineId);
            values.put("skuId", skuId);
            values.put("plannedQty", plannedQty);
            values.put("businessLotKey", businessLotKey == null ? "NO_LOT" : businessLotKey);
            values.put("sourceLotId", sourceLotId == null ? "NO_LOT" : sourceLotId);
            return values;
        }
    }
}
