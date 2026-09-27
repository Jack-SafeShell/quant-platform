package cn.iocoder.yudao.module.quant.api.backtest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PaperStartTokenRequest(
        @Pattern(regexp = "[0-9a-f]{64}") String previewHash,
        @Pattern(regexp = "CONFIRM_DRY_RUN_START") String confirmation,
        @NotBlank @Size(max = 500) String comment) {}
