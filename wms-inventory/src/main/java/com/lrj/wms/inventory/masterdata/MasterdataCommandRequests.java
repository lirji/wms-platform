package com.lrj.wms.inventory.masterdata;

import java.math.BigDecimal;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** MasterdataCommandController 的协议 DTO；不直接暴露数据库对象。 */
public final class MasterdataCommandRequests {
    private MasterdataCommandRequests() { }
    /** CreateWarehouseRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record CreateWarehouseRequest(
            @Size(max = 64) String clientOperationId,
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Size(max = 64) String timezone) { }
    /** CreateLocationRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record CreateLocationRequest(
            @Size(max = 64) String clientOperationId,
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 32) String zoneCode,
            @NotBlank @Size(max = 32) String locationType,
            @Digits(integer = 14, fraction = 6) @DecimalMin(value = "0", inclusive = true) BigDecimal capacityQty,
            @Size(max = 32) String capacityUnit) { }
    /** CreateSkuRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record CreateSkuRequest(
            @Size(max = 64) String clientOperationId,
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Size(max = 32) String baseUnit,
            @NotNull @Min(0) @Max(6) Integer quantityScale,
             Boolean lotEnabled,
             Boolean serialEnabled,
             Boolean expiryEnabled) { }
    /** AddSkuUnitRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record AddSkuUnitRequest(
            @Size(max = 64) String clientOperationId,
            @NotBlank @Size(max = 32) String unitCode,
            @NotNull @Digits(integer = 14, fraction = 0) @DecimalMin(value = "0", inclusive = false) BigDecimal numerator,
            @NotNull @Digits(integer = 14, fraction = 0) @DecimalMin(value = "0", inclusive = false) BigDecimal denominator,
            @Digits(integer = 14, fraction = 6) @DecimalMin(value = "0", inclusive = true) BigDecimal sampleQuantity) { }
    /** CreateLotRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record CreateLotRequest(
            @Size(max = 64) String clientOperationId,
            @NotBlank @Size(max = 64) String ownerId,
            @NotBlank @Size(max = 64) String skuId,
            @NotBlank @Size(max = 64) String lotCode,
            @NotBlank @Size(max = 64) String businessLotKey,
            @Size(max = 64) String producedAt,
            @Size(max = 64) String expiresAt,
            @Size(max = 32) String sourceDate,
            @Min(0) Long expiryRuleVersion) { }
}
