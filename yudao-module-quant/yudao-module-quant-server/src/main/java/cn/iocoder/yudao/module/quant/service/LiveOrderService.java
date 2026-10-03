package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.*;
import cn.iocoder.yudao.module.quant.dal.*;
import cn.iocoder.yudao.module.quant.engine.*;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.*;

@Service
public class LiveOrderService {
    private final LiveControlRepository controls;private final LiveOrderRepository orders;private final LiveTradingClient client;private final QuantProperties properties;private final LiveControlService controlService;private final TransactionTemplate transactions;
    public LiveOrderService(LiveControlRepository controls,LiveOrderRepository orders,LiveTradingClient client,QuantProperties properties,LiveControlService controlService,PlatformTransactionManager transactionManager){this.controls=controls;this.orders=orders;this.client=client;this.properties=properties;this.controlService=controlService;this.transactions=new TransactionTemplate(transactionManager);}

    @Transactional public Map<String,Object> issue(long tenant,long owner,String policyId,LiveOrderTokenRequest request){
        if(!"CONFIRM_LIVE_ORDER".equals(request.confirmation()))throw new IllegalArgumentException("真实订单确认语不匹配");
        var decision=controls.decision(tenant,owner,request.clientOrderId());
        requireOrderReady(tenant,owner,policyId,decision);
        if(decision==null||!policyId.equals(decision.get("policyId"))||!"ALLOWED_OFFLINE".equals(decision.get("decision"))||Boolean.TRUE.equals(decision.get("executed")))throw new IllegalArgumentException("订单决策不存在、未通过或已执行");
        if(orders.byClient(tenant,owner,request.clientOrderId())!=null)throw new IllegalArgumentException("订单已创建");
        orders.revokeTokens(policyId);byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String raw=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);String hash=DatasetRegistry.hash(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));String id=UUID.randomUUID().toString();long expires=System.currentTimeMillis()+properties.getLiveOrderTokenTtlSeconds()*1000L;
        orders.issueToken(id,policyId,String.valueOf(decision.get("id")),tenant,owner,hash,expires);controls.audit(policyId,tenant,owner,owner,"LIVE_ORDER_TOKEN_ISSUED",String.valueOf(controls.get(tenant,owner,policyId).get("status")),String.valueOf(controls.get(tenant,owner,policyId).get("status")),request.clientOrderId()+" / "+request.comment().trim());return Map.of("token",raw,"expiresAt",expires,"clientOrderId",request.clientOrderId());
    }

    public Map<String,Object> execute(long tenant,long owner,String policyId,LiveOrderExecuteRequest request){
        String hash=DatasetRegistry.hash(request.token().getBytes(java.nio.charset.StandardCharsets.UTF_8));var tokenView=orders.token(tenant,owner,policyId,hash);if(tokenView==null)throw new IllegalArgumentException("真实订单令牌无效、过期或已使用");var decisionView=decisionById(tenant,owner,String.valueOf(tokenView.get("decisionId")));requireOrderReady(tenant,owner,policyId,decisionView);Reservation reservation=transactions.execute(status->{orders.lockAccount();requireOrderReady(tenant,owner,policyId,decisionView);validatePortfolioOrder(tenant,owner,policyId,decisionView);validateExchangeRisk(tenant,owner,policyId,decisionView);var token=orders.token(tenant,owner,policyId,hash);long now=System.currentTimeMillis();if(token==null||!orders.consume(String.valueOf(token.get("id")),now))throw new IllegalArgumentException("真实订单令牌无效、过期或已使用");var decision=decisionById(tenant,owner,String.valueOf(token.get("decisionId")));String clientId=String.valueOf(decision.get("clientOrderId"));var existing=orders.byClient(tenant,owner,clientId);if(existing!=null)return new Reservation(existing,null,null,null,null,null);BigDecimal price=decimal(decision,"price"),amount=decimal(decision,"amount"),notional=decimal(decision,"notional");String id=UUID.randomUUID().toString();orders.createSubmitting(id,policyId,String.valueOf(decision.get("id")),tenant,owner,clientId,properties.getLivePair().replace('/','-'),String.valueOf(decision.get("side")),String.valueOf(decision.get("orderType")),price,amount,notional,now+properties.getLiveOrderCancelTimeoutSeconds()*1000L);return new Reservation(null,id,clientId,String.valueOf(decision.get("side")),price,amount);});
        if(reservation.existing()!=null)return reservation.existing();String id=reservation.id(),clientId=reservation.clientId();BigDecimal price=reservation.price(),amount=reservation.amount();
        try{var response=parse(client.placeSpotLimitOrder(clientId,reservation.side(),plain(price),plain(amount)));var item=first(response);String code=text(response,"code"),sCode=text(item,"sCode");if(!"0".equals(code)||!"0".equals(sCode)){String message=text(item,"sMsg");orders.failed(id,sCode,message);throw new IllegalStateException("OKX 下单拒绝: "+sCode+" "+message);}orders.accepted(id,text(item,"ordId"),sCode,text(item,"sMsg"));controls.audit(policyId,tenant,owner,owner,"LIVE_ORDER_SUBMITTED",String.valueOf(controls.get(tenant,owner,policyId).get("status")),String.valueOf(controls.get(tenant,owner,policyId).get("status")),clientId+" / "+text(item,"ordId"));return orders.get(tenant,owner,id);}catch(RuntimeException e){var current=orders.get(tenant,owner,id);if(current!=null&&"SUBMITTING".equals(current.get("status")))orders.uncertain(id,safe(e.getMessage()));throw e;}
    }

    public Map<String,Object> refresh(long tenant,long owner,String orderId){var order=owned(tenant,owner,orderId);return refreshOrder(tenant,owner,order);}
    public Map<String,Object> cancel(long tenant,long owner,String orderId){var order=owned(tenant,owner,orderId);cancelOrder(tenant,owner,order,"MANUAL_CANCEL");return refreshOrder(tenant,owner,orders.get(tenant,owner,orderId));}
    public List<Map<String,Object>> list(long tenant,long owner,String policyId){if(controls.get(tenant,owner,policyId)==null)throw new IllegalArgumentException("实盘安全策略不存在");return orders.list(tenant,owner,policyId);}
    public void reconcilePolicy(long tenant,long owner,String policyId){for(var order:orders.reconcilable(policyId)){if(((Number)order.get("tenantId")).longValue()==tenant&&((Number)order.get("ownerId")).longValue()==owner)refreshOrder(tenant,owner,order);}}
    public BigDecimal availablePosition(long tenant,long owner,String policy,String session){return LiveAccountBudget.available(orders.inventoryRows(tenant,owner,policy,session));}
    public Map<String,Object> inventory(long tenant,long owner,String policy,String session){
        if(controls.get(tenant,owner,policy)==null)throw new IllegalArgumentException("实盘安全策略不存在");
        if(session!=null&&!orders.ownsSession(tenant,owner,policy,session))throw new IllegalArgumentException("自动实盘会话不存在");
        var rows=orders.inventoryRows(tenant,owner,policy,session);var result=LivePerformanceService.calculate(rows,null,null);
        result.put("inventoryOwner",session==null?"POLICY_MANUAL":session);result.put("sellableBtc",LiveAccountBudget.available(rows));
        result.put("accountScope","SINGLE_CONFIGURED_OKX_ACCOUNT");result.put("fundsReservedByPortfolio",false);return result;
    }
    public int accountOpenCount(){return orders.accountActive().size();}
    public int openCount(String policyId){return orders.openCount(policyId);}
    public BigDecimal dailyNotional(String policyId,long since){return orders.dailyNotional(policyId,since);}
    public String emergencyStop(long tenant,long owner,String policyId,LiveControlStopRequest request){var open=orders.open(policyId);for(var order:open){if(((Number)order.get("tenantId")).longValue()==tenant&&((Number)order.get("ownerId")).longValue()==owner)try{cancelOrder(tenant,owner,order,"EMERGENCY_STOP");}catch(RuntimeException ignored){}}return controlService.emergencyStop(tenant,owner,policyId,request);}

    @Scheduled(fixedDelay=10000) public void cancelExpired(){if(!properties.isLiveExecutionEnabled()||!client.configured())return;for(var row:orders.expired(System.currentTimeMillis()))try{cancelOrder(((Number)row.get("tenantId")).longValue(),((Number)row.get("ownerId")).longValue(),row,"AUTO_TIMEOUT");}catch(RuntimeException ignored){}}

    private void requireOrderReady(long tenant,long owner,String policy,Map<String,Object> decision){
        if(decision==null||!policy.equals(decision.get("policyId")))throw new IllegalArgumentException("Order decision not found");
        boolean exit=orders.ownedExit(tenant,owner,policy,String.valueOf(decision.get("clientOrderId")));
        if(exit&&!"SELL".equals(decision.get("side")))throw new IllegalArgumentException("Exit orders can only sell");
        if(exit){
            controlService.strategy(tenant,owner,policy);var row=controls.get(tenant,owner,policy);
            if(row==null||!Set.of("HALTED","ARMED_OFFLINE").contains(String.valueOf(row.get("status")))||!properties.isLiveExecutionEnabled()||!client.configured())throw new IllegalArgumentException("Exit execution disabled");
        }else requireLiveReady(tenant,owner,policy);
    }
    public void checkSessionStart(long tenant,long owner,String policy,String portfolio){
        orders.lockAccount();var repo=orders.portfolios();var active=repo.active();
        if(portfolio==null){if(!active.isEmpty()||repo.otherPolicySessions(policy))throw new IllegalArgumentException("Use a controlled portfolio for multiple strategies");}
        else {var row=repo.get(tenant,owner,portfolio);if(row==null||!"RUNNING".equals(row.get("status"))||repo.members(portfolio).stream().noneMatch(m->policy.equals(m.get("policyId"))))throw new IllegalArgumentException("Portfolio membership not valid");}
    }
    public BigDecimal sessionLoss(long tenant,long owner,String policy,String session,BigDecimal mark,BigDecimal fallback){
        if(orders.portfolios().membership(session)==null)return fallback;
        var result=LivePerformanceService.calculate(orders.inventoryRows(tenant,owner,policy,session),mark,null);
        if(!Boolean.TRUE.equals(result.get("valuationComplete")))throw new IllegalArgumentException("Portfolio costs incomplete");
        return ((BigDecimal)result.get("netContribution")).negate().max(BigDecimal.ZERO);
    }
    private void validatePortfolioOrder(long tenant,long owner,String policy,Map<String,Object> decision){
        var repo=orders.portfolios();String session=orders.sessionForClient(tenant,owner,policy,String.valueOf(decision.get("clientOrderId")));
        var member=session==null?null:repo.membership(session);var active=repo.active();
        boolean exit="SELL".equals(decision.get("side"))&&orders.ownedExit(tenant,owner,policy,String.valueOf(decision.get("clientOrderId")));
        if(exit){if(!active.isEmpty())throw new IllegalArgumentException("Stop portfolio before exiting old inventory");return;}
        if(!active.isEmpty()&&(member==null||!"RUNNING".equals(member.get("status"))))throw new IllegalArgumentException("Order outside running portfolio");
        if(member==null)return;
        if(!"RUNNING".equals(member.get("status")))throw new IllegalArgumentException("Portfolio is stopping or stopped");
        var rows=orders.inventoryRows(tenant,owner,policy,session);BigDecimal price=decimal(decision,"price");
        LivePortfolioBudget.requireOrder(rows,price,decimal(decision,"notional"),String.valueOf(decision.get("side")),decimal(member,"capital"),decimal(member,"dailyNotional"),repo.daily(tenant,owner,policy,session,dayStart()));
        var valuations=new ArrayList<Map<String,Object>>();
        for(var allocation:repo.members(String.valueOf(member.get("id")))){
            if(allocation.get("sessionId")==null)throw new IllegalArgumentException("Portfolio session missing");
            valuations.add(LivePerformanceService.calculate(orders.inventoryRows(tenant,owner,String.valueOf(allocation.get("policyId")),String.valueOf(allocation.get("sessionId"))),price,null));
        }
        LivePortfolioBudget.requireLoss(valuations,decimal(member,"lossBudget"));
    }
    public static long dayStart(){return java.time.LocalDate.now(java.time.ZoneOffset.UTC).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();}
    private void requireLiveReady(long tenant,long owner,String policyId){controlService.strategy(tenant,owner,policyId);var policy=controls.get(tenant,owner,policyId);if(policy==null)throw new IllegalArgumentException("实盘安全策略不存在");if(!"ARMED_OFFLINE".equals(policy.get("status")))throw new IllegalArgumentException("实盘策略未启用");if(!properties.isLiveExecutionEnabled())throw new IllegalArgumentException("真实执行总开关关闭");if(!client.configured())throw new IllegalArgumentException("OKX 凭据未配置");}
    private void validateExchangeRisk(long tenant,long owner,String policyId,Map<String,Object> decision){BigDecimal notional=decimal(decision,"notional"),amount=decimal(decision,"amount");var pending=parse(client.pendingOrders());if(!"0".equals(text(pending,"code")))throw new IllegalArgumentException("无法核对交易所挂单");Object pendingData=pending.get("data");int open=pendingData instanceof List<?> list?list.size():Integer.MAX_VALUE;var active=orders.accountActive();
        if(!(pendingData instanceof List<?> pendingRows))throw new IllegalArgumentException("交易所挂单响应不完整");
        var known=new HashSet<String>();for(var row:active)known.add(String.valueOf(row.get("clientOrderId")));
        for(Object row:pendingRows)if(!(row instanceof Map<?,?> item)||!known.contains(String.valueOf(item.get("clOrdId"))))throw new IllegalArgumentException("交易所存在未归属挂单，请先对账");
        if(Math.max(open,active.size())>=properties.getLiveMaxOpenOrders())throw new IllegalArgumentException("交易所挂单数已达到上限");var balance=parse(client.accountBalance());if(!"0".equals(text(balance,"code")))throw new IllegalArgumentException("无法核对交易所仓位");BigDecimal exposure=BigDecimal.ZERO,availableBtc=BigDecimal.ZERO;Object data=balance.get("data");if(!(data instanceof List<?> accounts)||accounts.isEmpty()||!(accounts.getFirst() instanceof Map<?,?> account))throw new IllegalArgumentException("交易所仓位响应不完整");Object details=account.get("details");if(!(details instanceof List<?> currencies))throw new IllegalArgumentException("交易所仓位明细缺失");for(Object value:currencies)if(value instanceof Map<?,?> currency&&"BTC".equals(String.valueOf(currency.get("ccy")))){String eqUsd=String.valueOf(currency.get("eqUsd")),avail=String.valueOf(currency.get("availBal"));if(!eqUsd.isBlank()&&!"null".equals(eqUsd))exposure=new BigDecimal(eqUsd);if(!avail.isBlank()&&!"null".equals(avail))availableBtc=new BigDecimal(avail);}if("BUY".equals(decision.get("side"))&&exposure.add(LiveAccountBudget.pendingBuys(active)).add(notional).compareTo(properties.getLiveMaxTotalExposure())>0)throw new IllegalArgumentException("交易所实际仓位将超过上限");if("SELL".equals(decision.get("side"))){String session=orders.sessionForClient(tenant,owner,policyId,String.valueOf(decision.get("clientOrderId")));LiveAccountBudget.requireSell(orders.inventoryRows(tenant,owner,policyId,session),amount);}
        if("SELL".equals(decision.get("side"))&&availableBtc.compareTo(amount)<0)throw new IllegalArgumentException("可用 BTC 不足以卖出");long dayStart=java.time.LocalDate.now(java.time.ZoneOffset.UTC).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();if(orders.accountDailyNotional(dayStart).add(notional).compareTo(properties.getLiveMaxDailyNotional())>0)throw new IllegalArgumentException("账户共享日订单预约金额将超过上限");}
    private Map<String,Object> owned(long tenant,long owner,String id){var order=orders.get(tenant,owner,id);if(order==null)throw new IllegalArgumentException("真实订单不存在");return order;}
    private Map<String,Object> decisionById(long tenant,long owner,String id){var decision=controls.decisionById(tenant,owner,id);if(decision==null)throw new IllegalArgumentException("订单决策不存在");return decision;}
    private Map<String,Object> refreshOrder(long tenant,long owner,Map<String,Object> order){String status=String.valueOf(order.get("status"));boolean terminal=List.of("FILLED","CANCELED").contains(status);if(!terminal&&!List.of("LIVE","PARTIALLY_FILLED","CANCEL_REQUESTED","SUBMIT_UNKNOWN","SUBMITTING","UNKNOWN").contains(status))return order;var response=parse(client.getOrder(String.valueOf(order.get("clientOrderId"))));var item=first(response);requireOk(response,item,"查询订单");String mapped=mapState(text(item,"state"));if(terminal)validateTerminalRefresh(order,item,mapped);orders.updateCosts(String.valueOf(order.get("id")),mapped,number(item,"accFillSz"),nullableNumber(item,"avgPx"),text(item,"sCode"),text(item,"sMsg"),nullableNumber(item,"fee"),text(item,"feeCcy"),item.containsKey("rebate") && text(item,"rebate").isBlank() ? BigDecimal.ZERO : nullableNumber(item,"rebate"),text(item,"rebateCcy"));return orders.get(tenant,owner,String.valueOf(order.get("id")));}
    // Historical cost repair queries the exchange only; final execution facts cannot regress.
    private static void validateTerminalRefresh(Map<String,Object> order,Map<String,Object> item,String mapped){
        if(!mapped.equals(order.get("status"))||number(item,"accFillSz").compareTo(decimal(order,"filledAmount"))!=0)
            throw new IllegalStateException("Historical order execution facts changed");
        BigDecimal filled=decimal(order,"filledAmount");
        if(filled.signum()>0){
            BigDecimal stored=nullableDecimal(order,"averagePrice"),actual=nullableNumber(item,"avgPx");
            if(stored==null||actual==null||stored.compareTo(actual)!=0)
                throw new IllegalStateException("Historical order execution price changed");
            if(nullableNumber(item,"fee")==null||text(item,"feeCcy").isBlank()||!item.containsKey("rebate"))
                throw new IllegalStateException("Historical order cost evidence is incomplete");
        }
        if(!text(item,"clOrdId").isBlank()&&!text(item,"clOrdId").equals(text(order,"clientOrderId")))
            throw new IllegalStateException("Historical order identity changed");
    }
    private void cancelOrder(long tenant,long owner,Map<String,Object> order,String reason){String status=String.valueOf(order.get("status"));if(!List.of("SUBMITTING","LIVE","PARTIALLY_FILLED").contains(status))return;var response=parse(client.cancelOrder(String.valueOf(order.get("clientOrderId"))));var item=first(response);requireOk(response,item,"撤销订单");orders.update(String.valueOf(order.get("id")),"CANCEL_REQUESTED",decimal(order,"filledAmount"),nullableDecimal(order,"averagePrice"),text(item,"sCode"),reason);controls.audit(String.valueOf(order.get("policyId")),tenant,owner,owner,reason,status,"CANCEL_REQUESTED",String.valueOf(order.get("clientOrderId")));}
    @SuppressWarnings("unchecked") private static Map<String,Object> parse(String json){return JsonUtils.parseObject(json,Map.class);}
    @SuppressWarnings("unchecked") private static Map<String,Object> first(Map<String,Object> response){Object data=response.get("data");if(!(data instanceof List<?> list)||list.isEmpty()||!(list.getFirst() instanceof Map<?,?>))throw new IllegalStateException("OKX 响应缺少订单数据");return (Map<String,Object>)list.getFirst();}
    private static void requireOk(Map<String,Object> response,Map<String,Object> item,String action){if(!"0".equals(text(response,"code"))||(!text(item,"sCode").isBlank()&&!"0".equals(text(item,"sCode"))))throw new IllegalStateException("OKX "+action+"失败: "+text(response,"code")+"/"+text(item,"sCode"));}
    private static String mapState(String state){return switch(state){case "live"->"LIVE";case "partially_filled"->"PARTIALLY_FILLED";case "filled"->"FILLED";case "canceled"->"CANCELED";default->"UNKNOWN";};}
    private static String text(Map<String,Object> map,String key){Object value=map.get(key);return value==null?"":String.valueOf(value);}
    private static BigDecimal number(Map<String,Object> map,String key){String value=text(map,key);return value.isBlank()?BigDecimal.ZERO:new BigDecimal(value);}
    private static BigDecimal nullableNumber(Map<String,Object> map,String key){String value=text(map,key);return value.isBlank()?null:new BigDecimal(value);}
    private static BigDecimal decimal(Map<String,Object> map,String key){return new BigDecimal(String.valueOf(map.get(key)));}
    private static BigDecimal nullableDecimal(Map<String,Object> map,String key){Object value=map.get(key);return value==null?null:new BigDecimal(String.valueOf(value));}
    private static String plain(BigDecimal value){return value.stripTrailingZeros().toPlainString();}
    private static String safe(String value){if(value==null)return "请求失败";return value.length()>450?value.substring(0,450):value;}
    private record Reservation(Map<String,Object> existing,String id,String clientId,String side,BigDecimal price,BigDecimal amount){}
}
