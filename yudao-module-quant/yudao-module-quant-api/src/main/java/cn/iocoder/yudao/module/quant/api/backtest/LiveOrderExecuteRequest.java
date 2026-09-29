package cn.iocoder.yudao.module.quant.api.backtest;

import jakarta.validation.constraints.NotBlank;

public record LiveOrderExecuteRequest(@NotBlank String token) {}
