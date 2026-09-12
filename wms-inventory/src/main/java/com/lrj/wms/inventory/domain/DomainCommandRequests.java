package com.lrj.wms.inventory.domain;

import java.math.BigDecimal;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** DomainCommandController 的协议 DTO；不直接暴露数据库对象。 */
public final class DomainCommandRequests {
    private DomainCommandRequests() { }
    /** MoveRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record MoveRequest(
            @Size(max = 64) String clientOperationId,
            @NotBlank @Size(max = 64) String sourceBalanceId,
            @NotBlank @Size(max = 64) String targetLocationId,
            @NotNull @Digits(integer = 14, fraction = 6) @DecimalMin(value = "0", inclusive = false) BigDecimal qty,
            @Size(max = 64) String unit,
            @NotBlank @Size(max = 128) String reason) { }
    /** HoldRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record HoldRequest(
            @Size(max = 64) String clientOperationId,
            @NotNull @Valid HoldScope scope,
            @Digits(integer = 14, fraction = 6) @DecimalMin(value = "0", inclusive = false) BigDecimal qty,
            @NotBlank @Size(max = 128) String reason,
            @Size(max = 200) List<@NotBlank @Size(max = 512) String> evidenceRefs) { }
    /** ReleaseRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record ReleaseRequest(
            @Size(max = 64) String clientOperationId,
            @Size(max = 128) String reason,
            @NotNull @Min(0) Long expectedVersion) { }
    /** CreateAdjustmentRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record CreateAdjustmentRequest(
            @Size(max = 64) String clientOperationId,
            @NotBlank @Size(max = 64) String balanceId,
            @NotNull @Digits(integer = 14, fraction = 6) BigDecimal deltaQty,
            @NotBlank @Size(max = 128) String reason,
            @Size(max = 64) String countLineId,
            @Size(max = 200) List<@NotBlank @Size(max = 512) String> evidenceRefs,
            @Size(max = 200) List<@NotBlank @Size(max = 512) String> serialActions) { }
    /** ApproveRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record ApproveRequest(
            @Size(max = 64) String clientOperationId,
            @NotBlank @Size(max = 64) String decision,
            @Size(max = 128) String reason,
            @NotNull @Min(0) Long expectedVersion) { }
    /** ApplyRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record ApplyRequest(
            @Size(max = 64) String clientOperationId,
            @NotNull @Min(0) Long expectedVersion) { }
    /** HoldScope：在数据库用例开始前校验类型、范围和必填项。 */
    public record HoldScope(
            @NotBlank @Size(max = 64) String balanceId,
            @Digits(integer = 14, fraction = 6) @DecimalMin(value = "0", inclusive = false) BigDecimal qty) { }
}
