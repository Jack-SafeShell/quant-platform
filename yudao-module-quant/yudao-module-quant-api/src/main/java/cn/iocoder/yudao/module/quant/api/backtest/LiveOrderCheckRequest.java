package cn.iocoder.yudao.module.quant.api.backtest;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record LiveOrderCheckRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String clientOrderId,
        @NotBlank @Pattern(regexp = "BUY|SELL") String side,
        @NotBlank @Pattern(regexp = "LIMIT") String orderType,
        @NotNull @DecimalMin("0.00000001") BigDecimal price,
        @NotNull @DecimalMin("0.00000001") BigDecimal amount,
        @NotNull @DecimalMin("0") BigDecimal currentExposure,
        @NotNull @DecimalMin("0") BigDecimal dailyExecutedNotional,
        @Min(0) int openOrders) {}
