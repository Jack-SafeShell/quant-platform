package cn.iocoder.yudao.module.quant.api.backtest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PaperSessionReviewRequest(
        @Pattern(regexp = "APPROVED|REJECTED") String decision,
        @NotBlank @Size(max = 500) String comment,
        @Pattern(regexp = "[0-9a-f]{64}") String admissionEvidenceHash) {}
