package cn.iocoder.yudao.module.quant.api.backtest;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record BreakoutStrategyRequest(
        @NotNull @Min(2) @Max(120) Integer entryPeriod,
        @NotNull @Min(2) @Max(120) Integer exitPeriod,
        @NotNull @DecimalMin("0.001") @DecimalMax("0.20") @Digits(integer=1,fraction=6) BigDecimal stopLossRatio,
        @NotNull @DecimalMin("0.001") @DecimalMax("0.50") @Digits(integer=1,fraction=6) BigDecimal takeProfitRatio) {}
