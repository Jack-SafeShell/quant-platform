package cn.iocoder.yudao.module.quant.api.backtest;

import jakarta.validation.constraints.NotBlank;

public record PaperSessionRequest(@NotBlank String batchId, @NotBlank String parameterSetId) {}
