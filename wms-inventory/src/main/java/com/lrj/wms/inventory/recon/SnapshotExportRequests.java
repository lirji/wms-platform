package com.lrj.wms.inventory.recon;

import java.math.BigDecimal;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** 协议请求模型，约束在开启数据库事务前验证。 */
public final class SnapshotExportRequests {
    private SnapshotExportRequests() { }
    /** CreateRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record CreateRequest(
            @NotNull @Size(min = 1, max = 1) List<@NotBlank @Size(max = 64) String> warehouseIds,
            @NotBlank @Size(max = 64) String cutoffId,
            @NotBlank @Size(max = 64) String cutoff,
            @Size(max = 64) String sourceWatermark,
            @Size(max = 64) String postingWatermark,
            @Size(max = 64) String receiptWatermark) { }
}
