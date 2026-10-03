package cn.iocoder.yudao.module.quant.service;
import java.math.BigDecimal;
import java.util.*;
public final class LivePortfolioBudget {
    private LivePortfolioBudget(){}
    public static void requireOrder(List<Map<String,Object>> rows,BigDecimal mark,BigDecimal notional,String side,BigDecimal capital,BigDecimal dailyCap,BigDecimal daily){
        var result=LivePerformanceService.calculate(rows,mark,null);
        if(!Boolean.TRUE.equals(result.get("valuationComplete")))throw new IllegalArgumentException("Portfolio cost evidence incomplete");
        BigDecimal exposure=((BigDecimal)result.get("netPositionBtc")).multiply(mark).add(LiveAccountBudget.pendingBuys(rows));
        BigDecimal availableCash=capital.add((BigDecimal)result.get("netCashFlow")).subtract(LiveAccountBudget.pendingBuys(rows));
        if("BUY".equals(side)&&notional.compareTo(availableCash)>0)throw new IllegalArgumentException("Strategy cash allocation exhausted");
        if("BUY".equals(side)&&exposure.add(notional).compareTo(capital)>0)throw new IllegalArgumentException("Strategy capital allocation exceeded");
        if(daily.add(notional).compareTo(dailyCap)>0)throw new IllegalArgumentException("Strategy daily allocation exceeded");
    }
    public static BigDecimal loss(List<Map<String,Object>> valuations){
        BigDecimal loss=BigDecimal.ZERO;
        for(var value:valuations){if(!Boolean.TRUE.equals(value.get("valuationComplete")))throw new IllegalArgumentException("Portfolio costs incomplete");loss=loss.add(((BigDecimal)value.get("netContribution")).negate().max(BigDecimal.ZERO));}
        return loss;
    }
    public static void requireLoss(List<Map<String,Object>> valuations,BigDecimal budget){if(loss(valuations).compareTo(budget)>=0)throw new IllegalArgumentException("Portfolio loss limit reached");}
}
