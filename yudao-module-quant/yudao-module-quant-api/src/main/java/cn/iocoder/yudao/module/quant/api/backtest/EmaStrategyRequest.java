package cn.iocoder.yudao.module.quant.api.backtest;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/** Fixed spot EMA template parameters; ratios use 0.02 for 2%. */
public record EmaStrategyRequest(
        @NotNull @Min(2) @Max(119) Integer fastPeriod,
        @NotNull @Min(3) @Max(120) Integer slowPeriod,
        @NotNull @DecimalMin("0.001") @DecimalMax("0.20") @Digits(integer = 1, fraction = 6) BigDecimal stopLossRatio,
        @NotNull @DecimalMin("0.001") @DecimalMax("0.50") @Digits(integer = 1, fraction = 6) BigDecimal takeProfitRatio) {
    @AssertTrue(message = "快 EMA 周期必须小于慢 EMA 周期")
    public boolean isPeriodOrderValid() {
        return fastPeriod == null || slowPeriod == null || fastPeriod < slowPeriod;
    }
}
