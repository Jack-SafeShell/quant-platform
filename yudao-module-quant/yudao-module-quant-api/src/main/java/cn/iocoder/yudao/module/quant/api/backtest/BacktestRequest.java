package cn.iocoder.yudao.module.quant.api.backtest;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/** Fixed strategy, BTC/USDT spot, 1h. Dates are UTC; end is exclusive. */
public record BacktestRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String requestKey,
        @NotBlank @Pattern(regexp = "[0-9a-f-]{36}") String strategyVersionId,
        @NotBlank @Pattern(regexp = "[0-9a-f-]{36}") String parameterSetId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String datasetId,
        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String startDate,
        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String endDate,
        @NotNull @DecimalMin("100") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2) BigDecimal startingBalance,
        @NotNull @DecimalMin("10") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2) BigDecimal stakeAmount,
        @NotNull @DecimalMin("0") @DecimalMax("0.01") @Digits(integer = 1, fraction = 6) BigDecimal fee) {
}
