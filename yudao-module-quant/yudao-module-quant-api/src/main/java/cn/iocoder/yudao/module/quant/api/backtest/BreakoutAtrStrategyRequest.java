package cn.iocoder.yudao.module.quant.api.backtest;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
public record BreakoutAtrStrategyRequest(
 @NotNull @Min(2) @Max(120) Integer entryPeriod,
 @NotNull @Min(2) @Max(120) Integer exitPeriod,
 @NotNull @Min(2) @Max(50) Integer atrPeriod,
 @NotNull @DecimalMin("1") @DecimalMax("5") @Digits(integer=1,fraction=2) BigDecimal atrMultiplier,
 @NotNull @DecimalMin("0.001") @DecimalMax("0.20") @Digits(integer=1,fraction=6) BigDecimal stopLossRatio,
 @NotNull @DecimalMin("0.001") @DecimalMax("0.50") @Digits(integer=1,fraction=6) BigDecimal takeProfitRatio) {}
