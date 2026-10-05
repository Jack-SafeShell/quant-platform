package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.dal.LiveOrderRepository;
import cn.iocoder.yudao.module.quant.engine.DatasetRegistry;
import cn.iocoder.yudao.module.quant.engine.LiveTradingClient;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.springframework.stereotype.Service;
import java.math.*;
import java.time.*;
import java.util.*;

/** Explicit exchange reads; never reserves funds, issues a token or sends an order. */
@Service
public class LiveRunPreflightService {
    private final LiveControlService controls;
    private final LiveTradingClient client;
    private final LiveOrderRepository orders;
    private final QuantProperties properties;
    public LiveRunPreflightService(LiveControlService controls,LiveTradingClient client,LiveOrderRepository orders,QuantProperties properties){this.controls=controls;this.client=client;this.orders=orders;this.properties=properties;}

    public Map<String,Object> check(long tenant,long owner,String report,BigDecimal order,BigDecimal loss,Integer fee,Integer slip){
        var plan=new TreeMap<>(controls.runPlan(tenant,owner,report,order,loss,fee,slip));
        var checks=new ArrayList<Map<String,Object>>((List<Map<String,Object>>)plan.get("checks"));
        plan.put("checks",checks);plan.put("preflightPerformed",true);plan.put("checkedAt",System.currentTimeMillis());
        plan.put("fundsReserved",false);plan.put("ordersSent",0);plan.put("quotePurpose","HYPOTHETICAL_FIRST_BUY");
        if(!passed(checks,"STRATEGY_CURRENT")||!passed(checks,"DOUBLE_CONFIRMED")||!passed(checks,"EXCHANGE_EVIDENCE_MATCH")||!passed(checks,"EXCHANGE_ACCOUNT_MATCH")){
            add(checks,"EXCHANGE_PREFLIGHT",false,"先匹配候选来源、门禁固定账户和准入确认；未查询私有账户");return finish(plan);
        }
        if(!client.configured()){add(checks,"EXCHANGE_PREFLIGHT",false,"当前部署未配置独立加密凭据；未查询交易所");return finish(plan);}
        var budget=(Map<String,Object>)plan.get("budget");var limits=(Map<String,Object>)plan.get("limits");
        BigDecimal selected=decimal(budget,"orderNotional"),notional=null;
        try {
            BigDecimal price=client.limitPrice("BUY",LiveAutomationService.executionPrice(client.marketTicker(),"BUY"));
            BigDecimal amount=client.limitAmount(selected.divide(price,8,RoundingMode.DOWN));
            if(price.signum()<=0||amount.signum()<=0)throw new IllegalArgumentException();
            notional=price.multiply(amount);if(notional.compareTo(selected)>0)throw new IllegalArgumentException();
            plan.put("orderPreview",Map.of("side","BUY","price",price,"amount",amount,"notional",notional,"budgetRemainder",selected.subtract(notional)));
            add(checks,"EXECUTION_QUOTE",true,"按实时卖价与交易所步长计算首次买入；未扩大预算");
            try{client.preflightSpotLimitOrder("BUY",price.toPlainString(),amount.toPlainString());add(checks,"TRADING_RULES_AND_PERMISSIONS",true,"交易所精度、最低订单、现货权限通过；Binance 另核对 BNB 费用边界");}
            catch(RuntimeException e){add(checks,"TRADING_RULES_AND_PERMISSIONS",false,"交易规则或权限未通过：请核对最低金额、精度、现货权限及手续费设置");}
        }catch(RuntimeException e){add(checks,"EXECUTION_QUOTE",false,"实时报价或数量步长无法核对；不使用历史价格替代");}
        BigDecimal exposure=null;
        try {
            var response=parse(client.accountBalance());var data=(List<?>)response.get("data");
            if(data.size()!=1||!(data.getFirst() instanceof Map<?,?> account)||!(account.get("details") instanceof List<?> currencies))throw new IllegalArgumentException();
            var usdt=currency(currencies,"USDT");var btc=currency(currencies,"BTC");
            BigDecimal free=number(usdt,"availBal"),btcFree=number(btc,"availBal");exposure=number(btc,"eqUsd");
            plan.put("funds",Map.of("availableUsdt",free,"availableBtc",btcFree,"btcExposureUsdt",exposure,"valuationScope","BTC_USDT_ONLY"));
            add(checks,"START_CASH_BUDGET",free.compareTo(selected)>=0,"可用 USDT "+free.toPlainString()+" / 所选预算 "+selected.toPlainString()+"；不使用锁定余额");
        }catch(RuntimeException e){add(checks,"START_CASH_BUDGET",false,"实际可用余额证据缺失或查询失败；不按零补造");}
        try {
            var response=parse(client.pendingOrders());if(!(response.get("data") instanceof List<?> pending))throw new IllegalArgumentException();
            var active=orders.accountActive();
            add(checks,"ACCOUNT_ORDERS_CLEAR",pending.isEmpty()&&active.isEmpty(),"交易所挂单 "+pending.size()+" / 平台账户活动订单 "+active.size()+"；新方案不接管旧挂单");
            long day=LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            BigDecimal used=orders.accountDailyNotional(day),cap=decimal(limits,"maxDailyNotional").min(properties.getLiveMaxDailyNotional());
            if(used==null||used.signum()<0)throw new IllegalArgumentException();
            plan.put("accountBudget",Map.of("dailyReservedNotional",used,"dailyRemainingNotional",cap.subtract(used).max(BigDecimal.ZERO)));
            add(checks,"DAILY_ORDER_BUDGET",notional!=null&&used.add(notional).compareTo(cap)<=0,"UTC 日累计预约 "+used.toPlainString()+" / 上限 "+cap.toPlainString()+" USDT");
            add(checks,"EXPOSURE_BUDGET",notional!=null&&exposure!=null&&exposure.add(LiveAccountBudget.pendingBuys(active)).add(notional).compareTo(decimal(limits,"maxTotalExposure").min(properties.getLiveMaxTotalExposure()))<=0,"账户 BTC 暴露与买单预约加首次买入金额不得超过总上限");
        }catch(RuntimeException e){add(checks,"ACCOUNT_BUDGET",false,"账户挂单或共享额度无法核对；不声明资金已预约");}
        return finish(plan);
    }
    private static Map<String,Object> finish(Map<String,Object> plan){var checks=(List<Map<String,Object>>)plan.get("checks");plan.put("readyForStartRequest",checks.stream().allMatch(c->Boolean.TRUE.equals(c.get("passed"))));plan.remove("evidenceHash");plan.put("evidenceHash",DatasetRegistry.hash(JsonUtils.toJsonByte(plan)));return plan;}
    private static void add(List<Map<String,Object>> checks,String id,boolean passed,String evidence){checks.add(Map.of("id",id,"passed",passed,"evidence",evidence));}
    private static boolean passed(List<Map<String,Object>> checks,String id){return checks.stream().anyMatch(c->id.equals(c.get("id"))&&Boolean.TRUE.equals(c.get("passed")));}
    private static Map<String,Object> parse(String json){var r=JsonUtils.parseObject(json,Map.class);if(!"0".equals(String.valueOf(r.get("code"))))throw new IllegalArgumentException();return r;}
    private static Map<?,?> currency(List<?> rows,String ccy){Map<?,?> found=null;for(Object row:rows)if(row instanceof Map<?,?> map&&ccy.equals(map.get("ccy"))){if(found!=null)throw new IllegalArgumentException();found=map;}if(found==null)throw new IllegalArgumentException();return found;}
    private static BigDecimal number(Map<?,?> row,String key){var value=new BigDecimal(Objects.requireNonNull(row.get(key)).toString());if(value.signum()<0)throw new IllegalArgumentException();return value;}
    private static BigDecimal decimal(Map<String,Object> row,String key){return new BigDecimal(String.valueOf(row.get(key)));}
}
