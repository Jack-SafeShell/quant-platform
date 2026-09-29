package cn.iocoder.yudao.module.quant.api.backtest;

import jakarta.validation.constraints.NotBlank;

public record LiveOrderTokenRequest(
        @NotBlank String clientOrderId,
        @NotBlank String confirmation,
        @NotBlank String comment) {}
