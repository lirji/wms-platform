package com.lrj.wms.inventory.effect;

import java.math.BigDecimal;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** EffectController 的协议 DTO；不直接暴露数据库对象。 */
public final class EffectRequests {
    private EffectRequests() { }
    /** RegisterRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record RegisterRequest(
            @Size(max = 64) String clientOperationId,
            @NotBlank @Size(max = 64) String action,
            @NotBlank @Size(max = 64) String factType,
            @NotBlank @Size(max = 64) String factParentId,
            @NotBlank @Size(max = 64) String factPartId,
            @NotBlank @Size(max = 64) String factLineId) { }
    /** CreateAttemptRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record CreateAttemptRequest(
            @Size(max = 64) String clientOperationId,
            @NotNull @Min(0) Long expectedEffectVersion,
            @Min(0) Long digestVersion,
            @Size(max = 64) String previousCommandId) { }
}
