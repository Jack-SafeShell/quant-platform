package cn.iocoder.yudao.module.quant.api.backtest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LiveAdmissionConfirmationRequest(
        @Pattern(regexp = "EVIDENCE_REVIEW|KEY_BOUNDARY_REVIEW") String confirmationType,
        @NotBlank @Size(max = 64) String confirmationPhrase,
        @NotBlank @Size(max = 500) String comment,
        @Pattern(regexp = "[0-9a-f]{64}") String reportHash) {}
