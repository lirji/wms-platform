package com.lrj.wms.inventory.count;

import java.math.BigDecimal;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** CountCommandController 的协议 DTO；不直接暴露数据库对象。 */
public final class CountCommandRequests {
    private CountCommandRequests() { }
    /** CreateRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record CreateRequest(
            @Size(max = 64) String planId,
            @Size(max = 64) String countPlanId,
            @Size(max = 32) String reason,
            @NotNull @Size(min = 1, max = 200) List<@NotBlank @Size(max = 64) String> locationIds) { }
    /** FreezeRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record FreezeRequest(
            @Size(max = 64) String phase) { }
    /** ObserveRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record ObserveRequest(
            @NotBlank @Size(max = 64) String lineId,
            @Size(max = 64) String observationId,
            @NotNull @Digits(integer = 14, fraction = 6) @DecimalMin(value = "0", inclusive = true) BigDecimal qty,
            @Min(1) Integer roundNo,
            @Valid com.lrj.wms.contract.messaging.SerialCountObservation serialObservation) {
        public ObserveRequest(String lineId,String observationId,BigDecimal qty,Integer roundNo) {this(lineId,observationId,qty,roundNo,null);}
    }
    /** ApproveRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record ApproveRequest(
            @Size(max = 64) String approvalId) { }
    /** ApplyRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record ApplyRequest(
            @NotBlank @Size(max = 64) String lineId,
            @Size(max = 64) String clientOperationId) { }
}
