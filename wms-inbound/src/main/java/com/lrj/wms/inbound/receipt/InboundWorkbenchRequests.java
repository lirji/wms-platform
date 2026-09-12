package com.lrj.wms.inbound.receipt;

import java.math.BigDecimal;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** InboundWorkbenchController 的协议 DTO；不直接暴露数据库对象。 */
public final class InboundWorkbenchRequests {
    private InboundWorkbenchRequests() { }
    /** CreateRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record CreateRequest(
            @Size(max = 64) String inboundOrderId,
            @NotBlank @Size(max = 64) String sourceSystem,
            @NotBlank @Size(max = 64) String externalNo,
            @NotBlank @Size(max = 64) String ownerId,
            @NotNull @Size(min = 1, max = 200) List<@Valid InboundLine> lines) { }
    /** ReceiveRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record ReceiveRequest(
            @NotBlank @Size(max = 64) String lineId,
            @Size(max = 64) String clientOperationId,
            @Size(max = 64) String receiptPartId,
            @Size(max = 64) String receiptSessionId,
            @Size(max = 64) String deviceId,
            @Size(max = 64) String deviceSessionId,
            @Min(0) Long scanSequence,
            @NotNull @Digits(integer = 14, fraction = 6) @DecimalMin(value = "0", inclusive = false) BigDecimal qty) {
        /** 设备观察字段必须成组提供，缺身份不能先进入数据库用例。 */
        @AssertTrue(message = "设备观察上下文不完整")
        public boolean isDeviceContextComplete() {
            return deviceId == null || deviceSessionId != null && !deviceSessionId.isBlank()
                    && receiptSessionId != null && !receiptSessionId.isBlank()
                    && receiptPartId != null && !receiptPartId.isBlank() && scanSequence != null && scanSequence > 0;
        }
    }
    /** InspectRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record InspectRequest(
            @NotBlank @Size(max = 64) String lineId,
            @NotNull @Digits(integer = 14, fraction = 6) @DecimalMin(value = "0", inclusive = true) BigDecimal acceptedQty,
            @NotNull @Digits(integer = 14, fraction = 6) @DecimalMin(value = "0", inclusive = true) BigDecimal rejectedQty,
            @Min(1) Long sourceVersion) { }
    /** ClaimRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record ClaimRequest(
            @NotNull @Min(0) Long expectedVersion,
            @Size(max = 64) String clientOperationId) { }
    /** PutawayRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record PutawayRequest(
            @NotBlank @Size(max = 64) String inboundOrderId,
            @NotBlank @Size(max = 64) String lineId,
            @Size(max = 64) String locationId,
            @Size(max = 64) String targetLocationId,
            @Size(max = 64) String locationType,
            @NotNull @Digits(integer = 14, fraction = 6) @DecimalMin(value = "0", inclusive = false) BigDecimal qty,
            @Size(max = 64) String clientOperationId) { }
    /** InboundLine：在数据库用例开始前校验类型、范围和必填项。 */
    public record InboundLine(
            @Size(max = 64) String lineId,
            @NotBlank @Size(max = 64) String externalLineId,
            @NotBlank @Size(max = 64) String skuId,
            @NotNull @Digits(integer = 14, fraction = 6) @DecimalMin(value = "0", inclusive = false) BigDecimal expectedQty,
            @Size(max = 32) String unit) { }
}
