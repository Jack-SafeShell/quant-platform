package cn.iocoder.yudao.module.quant.api.backtest;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record BollingerRsiStrategyRequest(
        @NotNull @Min(5) @Max(100) Integer bandPeriod,
        @NotNull @DecimalMin("1") @DecimalMax("4") @Digits(integer=1,fraction=2) BigDecimal bandDeviation,
        @NotNull @Min(2) @Max(50) Integer rsiPeriod,
        @NotNull @Min(10) @Max(70) Integer rsiCeiling,
        @NotNull @DecimalMin("0.001") @DecimalMax("0.20") @Digits(integer=1,fraction=6) BigDecimal stopLossRatio,
        @NotNull @DecimalMin("0.001") @DecimalMax("0.50") @Digits(integer=1,fraction=6) BigDecimal takeProfitRatio) {}
