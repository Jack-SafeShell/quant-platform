package cn.iocoder.yudao.module.quant.api.backtest;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
public record EmaAdxStrategyRequest(
 @NotNull @Min(2) @Max(119) Integer fastPeriod,
 @NotNull @Min(3) @Max(120) Integer slowPeriod,
 @NotNull @Min(2) @Max(50) Integer adxPeriod,
 @NotNull @Min(10) @Max(60) Integer adxThreshold,
 @NotNull @DecimalMin("0.001") @DecimalMax("0.20") @Digits(integer=1,fraction=6) BigDecimal stopLossRatio,
 @NotNull @DecimalMin("0.001") @DecimalMax("0.50") @Digits(integer=1,fraction=6) BigDecimal takeProfitRatio) {}
