package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import java.math.BigDecimal;
import java.util.Map;

public record LiveRunBudget(BigDecimal orderNotional, BigDecimal maxSessionLoss, int feeBps, int slippageBps) {
    public static LiveRunBudget resolve(QuantProperties p, BigDecimal policyCap, BigDecimal order, BigDecimal loss, Integer fee, Integer slip) {
        var budget = new LiveRunBudget(order == null ? p.getLiveAutomationOrderNotional() : order,
                loss == null ? p.getLiveMaxSessionLoss() : loss, fee == null ? 10 : fee, slip == null ? 5 : slip);
        BigDecimal cap = p.getLiveMaxOrderNotional().min(policyCap);
        if (budget.orderNotional().compareTo(new BigDecimal("0.01")) < 0 || budget.orderNotional().compareTo(cap) > 0
                || budget.maxSessionLoss().compareTo(new BigDecimal("0.01")) < 0 || budget.maxSessionLoss().compareTo(p.getLiveMaxSessionLoss()) > 0
                || budget.orderNotional().stripTrailingZeros().scale() > 8 || budget.maxSessionLoss().stripTrailingZeros().scale() > 8
                || budget.feeBps() < 0 || budget.feeBps() > 100 || budget.slippageBps() < 0 || budget.slippageBps() > 100)
            throw new IllegalArgumentException("运行预算须符合系统与门禁上限；成本假设须为 0～100 基点");
        return budget;
    }
    public Map<String,Object> snapshot() { return Map.of("orderNotional",orderNotional,"maxSessionLoss",maxSessionLoss,"feeBps",feeBps,"slippageBps",slippageBps); }
}
