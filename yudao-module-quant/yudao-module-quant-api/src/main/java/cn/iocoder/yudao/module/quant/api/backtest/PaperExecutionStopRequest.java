package cn.iocoder.yudao.module.quant.api.backtest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PaperExecutionStopRequest(@NotBlank @Size(max = 500) String comment) {}
