package cn.iocoder.yudao.module.quant.api.backtest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PaperAdmissionReviewRequest(
        @Pattern(regexp = "READY|NOT_READY") String decision,
        @NotBlank @Size(max = 500) String comment,
        @Pattern(regexp = "[0-9a-f]{64}") String evidenceHash) {}
