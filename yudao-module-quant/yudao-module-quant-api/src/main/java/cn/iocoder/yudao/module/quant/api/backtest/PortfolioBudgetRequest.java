package cn.iocoder.yudao.module.quant.api.backtest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public record PortfolioBudgetRequest(@NotNull BigDecimal totalCapital, Integer feeBps, Integer slippageBps,
        @NotNull @Size(min=1,max=10) List<@NotNull @Valid Allocation> allocations) {
    public record Allocation(@NotNull String reportId, @NotNull BigDecimal capital,
            @NotNull BigDecimal orderNotional, @NotNull BigDecimal maxSessionLoss, @NotNull BigDecimal dailyNotional) {}
}
