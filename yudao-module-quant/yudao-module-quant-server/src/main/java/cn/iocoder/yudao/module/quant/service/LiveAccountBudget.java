package cn.iocoder.yudao.module.quant.service;

import java.math.BigDecimal;
import java.util.*;

/** Reservations belong to the single configured account; inventory belongs to a session or manual policy. */
public final class LiveAccountBudget {
    private LiveAccountBudget() {}
    public static boolean active(Map<String,Object> row){return !Set.of("FAILED","FILLED","CANCELED").contains(String.valueOf(row.get("status")));}
    public static BigDecimal remaining(Map<String,Object> row){
        BigDecimal amount=decimal(row,"amount"),filled=decimal(row,"filledAmount");
        if(amount.signum()<0||filled.signum()<0||filled.compareTo(amount)>0)throw new IllegalArgumentException("订单预约数量证据异常");
        return amount.subtract(filled);
    }
    public static BigDecimal pendingBuys(List<Map<String,Object>> rows){
        BigDecimal reserved=BigDecimal.ZERO;
        for(var row:rows)if(active(row)&&"BUY".equals(row.get("side")))reserved=reserved.add(remaining(row).multiply(decimal(row,"price")));
        return reserved;
    }
    public static BigDecimal available(List<Map<String,Object>> rows){
        for(var row:rows)if(row.get("sessionOwners") instanceof Number owners && owners.intValue()>1)throw new IllegalArgumentException("Inventory ownership conflict");
        var performance=LivePerformanceService.calculate(rows,null,null);
        if(!Boolean.TRUE.equals(performance.get("costsComplete")))return BigDecimal.ZERO;
        BigDecimal net=(BigDecimal)performance.get("netPositionBtc"),reserved=BigDecimal.ZERO;
        for(var row:rows)if(active(row)&&"SELL".equals(row.get("side")))reserved=reserved.add(remaining(row));
        return net.subtract(reserved).max(BigDecimal.ZERO);
    }
    public static void requireSell(List<Map<String,Object>> rows,BigDecimal amount){
        if(amount.signum()<=0||amount.compareTo(available(rows))>0)throw new IllegalArgumentException("卖出超过所属会话或手动策略的净可用持仓（含费用与挂单预约）");
    }
    private static BigDecimal decimal(Map<String,Object> row,String key){Object value=row.get(key);return value==null?BigDecimal.ZERO:new BigDecimal(value.toString());}
}
