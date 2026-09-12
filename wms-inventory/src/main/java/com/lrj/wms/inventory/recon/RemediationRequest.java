package com.lrj.wms.inventory.recon;

import jakarta.validation.constraints.*;

/** 对账修复必须提交明确动作、理由与并发版本，禁止隐式修复。 */
public record RemediationRequest(@NotBlank @Size(max = 64) String approvedAction,
        @NotBlank @Size(max = 512) String reason, @NotNull @Min(0) Long expectedVersion) { }
