package cn.iocoder.yudao.module.quant.api.backtest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PaperExecutionStartRequest(
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String previewHash,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token) {}
