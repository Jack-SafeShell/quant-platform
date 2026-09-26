package cn.iocoder.yudao.module.quant.api.backtest;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record ParameterSetRequest(
        @NotNull @DecimalMin("100") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2) BigDecimal startingBalance,
        @NotNull @DecimalMin("10") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2) BigDecimal stakeAmount,
        @NotNull @DecimalMin("0") @DecimalMax("0.01") @Digits(integer = 1, fraction = 6) BigDecimal fee) {
}
