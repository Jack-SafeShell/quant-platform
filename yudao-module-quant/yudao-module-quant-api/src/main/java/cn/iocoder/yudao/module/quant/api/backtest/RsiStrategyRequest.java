package cn.iocoder.yudao.module.quant.api.backtest;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record RsiStrategyRequest(
        @NotNull @Min(2) @Max(50) Integer rsiPeriod,
        @NotNull @Min(1) @Max(98) Integer entryThreshold,
        @NotNull @Min(2) @Max(99) Integer exitThreshold,
        @NotNull @DecimalMin("0.001") @DecimalMax("0.20") @Digits(integer=1,fraction=6) BigDecimal stopLossRatio,
        @NotNull @DecimalMin("0.001") @DecimalMax("0.50") @Digits(integer=1,fraction=6) BigDecimal takeProfitRatio) {}
