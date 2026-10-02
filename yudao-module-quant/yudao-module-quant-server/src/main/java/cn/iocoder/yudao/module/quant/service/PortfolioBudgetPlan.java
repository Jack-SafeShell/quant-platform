package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.module.quant.api.backtest.PortfolioBudgetRequest;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import java.math.BigDecimal;
import java.util.*;

/** Offline allocation arithmetic; never reserves funds or changes execution limits. */
public final class PortfolioBudgetPlan {
    private PortfolioBudgetPlan() {}
    public static Map<String,Object> calculate(QuantProperties p, PortfolioBudgetRequest request) {
        if(request==null||request.allocations()==null||request.allocations().isEmpty()||request.allocations().size()>10)
            throw new IllegalArgumentException("Select one to ten strategy reports");
        amount(request.totalCapital());
        if(request.totalCapital().compareTo(p.getLiveMaxTotalExposure())>0)
            throw new IllegalArgumentException("Portfolio capital exceeds the system exposure limit");
        BigDecimal capital=BigDecimal.ZERO,loss=BigDecimal.ZERO,daily=BigDecimal.ZERO;
        Set<String> reports=new HashSet<>();
        for(var row:request.allocations()){
            if(row==null||row.reportId()==null||row.reportId().isBlank()||!reports.add(row.reportId()))
                throw new IllegalArgumentException("Strategy reports must be unique");
            amount(row.capital());amount(row.dailyNotional());
            var budget=LiveRunBudget.resolve(p,p.getLiveMaxOrderNotional(),row.orderNotional(),row.maxSessionLoss(),request.feeBps(),request.slippageBps());
            if(row.orderNotional()==null||row.maxSessionLoss()==null||budget.orderNotional().compareTo(row.capital())>0
                    ||budget.maxSessionLoss().compareTo(row.capital())>0||budget.orderNotional().compareTo(row.dailyNotional())>0)
                throw new IllegalArgumentException("Each capital and daily budget must cover its order; loss must fit capital");
            capital=capital.add(row.capital());loss=loss.add(budget.maxSessionLoss());daily=daily.add(row.dailyNotional());
        }
        if(capital.compareTo(request.totalCapital())>0||loss.compareTo(p.getLiveMaxSessionLoss())>0||daily.compareTo(p.getLiveMaxDailyNotional())>0)
            throw new IllegalArgumentException("Combined capital, loss or daily budgets exceed the portfolio limits");
        return Map.of("totalCapital",request.totalCapital(),"allocatedCapital",capital,"unallocatedCapital",request.totalCapital().subtract(capital),
                "combinedLossBudget",loss,"combinedDailyNotional",daily,"maxTotalExposure",p.getLiveMaxTotalExposure(),
                "maxSessionLoss",p.getLiveMaxSessionLoss(),"maxDailyNotional",p.getLiveMaxDailyNotional());
    }
    private static void amount(BigDecimal value){
        if(value==null||value.compareTo(new BigDecimal("0.01"))<0||value.stripTrailingZeros().scale()>8)
            throw new IllegalArgumentException("Budget amounts must be positive with at most eight decimals");
    }
}
