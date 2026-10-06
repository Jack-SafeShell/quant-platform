package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.*;
import cn.iocoder.yudao.module.quant.dal.*;
import cn.iocoder.yudao.module.quant.engine.*;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class LiveAutomationService {
    private final LiveSamplingDiagnostics sampling=new LiveSamplingDiagnostics();
    private final LiveAutomationRepository repository;private final LiveControlRepository controlRepository;private final LiveControlService controls;private final LiveOrderService orders;private final LiveTradingClient client;private final QuantProperties properties;
    public LiveAutomationService(LiveAutomationRepository repository,LiveControlRepository controlRepository,LiveControlService controls,LiveOrderService orders,LiveTradingClient client,QuantProperties properties){this.repository=repository;this.controlRepository=controlRepository;this.controls=controls;this.orders=orders;this.client=client;this.properties=properties;}

    @org.springframework.transaction.annotation.Transactional
    public String start(long tenant,long owner,String policyId,LiveAutomationStartRequest request){
        return startScoped(tenant,owner,policyId,request,null);
    }
    @org.springframework.transaction.annotation.Transactional
    public String startPortfolio(long tenant,long owner,String policyId,LiveAutomationStartRequest request,String portfolio){return startScoped(tenant,owner,policyId,request,portfolio);}
    private String startScoped(long tenant,long owner,String policyId,LiveAutomationStartRequest request,String portfolio){
        if(!"CONFIRM_AUTO_LIVE_START".equals(request.confirmation()))throw new IllegalArgumentException("自动实盘确认语不匹配");
        if(!properties.isLiveExecutionEnabled()||!properties.isLiveAutomationEnabled())throw new IllegalArgumentException("真实执行或自动策略总开关关闭");
        orders.checkSessionStart(tenant,owner,policyId,portfolio);
        var policy=controlRepository.getForUpdate(tenant,owner,policyId);if(policy==null||!"ARMED_OFFLINE".equals(policy.get("status")))throw new IllegalArgumentException("实盘策略未启用");var budget=LiveRunBudget.resolve(properties,decimal(policy,"maxOrderNotional"),request.orderNotional(),request.maxSessionLoss(),request.feeBps(),request.slippageBps());
        controls.strategy(tenant,owner,policyId);
        if(!client.configured())throw new IllegalArgumentException("交易所凭据未配置");var existing=repository.active(tenant,owner,policyId);if(existing!=null){
            if(budget.orderNotional().compareTo(decimal(existing,"orderNotional"))!=0 || budget.maxSessionLoss().compareTo(decimal(existing,"maxSessionLoss"))!=0)
                throw new IllegalArgumentException("已有运行会话使用不同预算，请先停止原会话");
            var recorded=get(tenant,owner,String.valueOf(existing.get("id"))).get("runConfiguration");
            if(recorded instanceof tools.jackson.databind.JsonNode config) {
                if(config.path("feeBps").asInt()!=budget.feeBps() || config.path("slippageBps").asInt()!=budget.slippageBps())
                    throw new IllegalArgumentException("已有会话使用不同成本假设，请先停止原会话");
            } else if(request.feeBps()!=null || request.slippageBps()!=null)
                throw new IllegalArgumentException("旧会话没有成本快照，不能修改其运行配置");
            return String.valueOf(existing.get("id"));
        }
        Account account=account(budget.orderNotional());String id=repository.create(policyId,tenant,owner,budget.orderNotional(),budget.maxSessionLoss(),account.totalEquity());controlRepository.audit(policyId,tenant,owner,owner,"AUTO_SESSION_STARTED","ARMED_OFFLINE","ARMED_OFFLINE",request.comment());
        var config=new TreeMap<String,Object>(budget.snapshot());config.put("sessionId",id);config.put("reportHash",policy.get("admissionReportHash"));config.put("accountId",policy.get("accountId"));config.put("exchangeName",policy.get("exchangeName"));
        controlRepository.audit(policyId,tenant,owner,owner,"AUTO_SESSION_CONFIG","ARMED_OFFLINE","ARMED_OFFLINE",JsonUtils.toJsonString(config));return id;
    }

    public String stop(long tenant,long owner,String sessionId,String reason){var session=owned(tenant,owner,sessionId);String policy=String.valueOf(session.get("policyId"));controls.requireAccount(tenant,owner,policy);repository.stop(sessionId,"STOPPED",reason);orders.emergencyStop(tenant,owner,policy,new LiveControlStopRequest("自动会话停止: "+reason));return "STOPPED";}
    public List<Map<String,Object>> list(long tenant,long owner,String policy){if(controlRepository.get(tenant,owner,policy)==null)throw new IllegalArgumentException("实盘安全策略不存在");var sessions=repository.list(tenant,owner,policy);sessions.forEach(session->bindAccount(tenant,owner,session));return sessions;}
    public Map<String,Object> get(long tenant,long owner,String id){var session=owned(tenant,owner,id);bindAccount(tenant,owner,session);session.put("strategy",controls.strategy(tenant,owner,String.valueOf(session.get("policyId"))));for(var audit:controlRepository.audits(tenant,owner,String.valueOf(session.get("policyId"))))
            if("AUTO_SESSION_CONFIG".equals(audit.get("eventType"))){var config=JsonUtils.getObjectMapper().readTree(String.valueOf(audit.get("message")));if(id.equals(config.path("sessionId").asText()))session.put("runConfiguration",config);}
        session.put("signals",repository.signals(tenant,owner,id));session.put("reconciliations",repository.snapshots(tenant,owner,id));session.put("alerts",repository.alerts(tenant,owner,id));session.put("samplingDiagnostics",sampling.get(tenant,owner,id));return session;}

    @Scheduled(fixedDelayString="#{${yudao.quant.live-automation-interval-seconds:15} * 1000}") public void tick(){if(!properties.isLiveExecutionEnabled()||!properties.isLiveAutomationEnabled()||!client.configured())return;for(var session:repository.active())try{process(session);}catch(RuntimeException e){fail(session,"AUTOMATION_FAILURE",safe(e.getMessage()));}}
    public void tick(String id,long tenant,long owner){process(owned(tenant,owner,id));}
    @EventListener(ApplicationReadyEvent.class) public void recoverInterrupted(){var interrupted=repository.active();if(interrupted.isEmpty())return;repository.failInterrupted();for(var row:interrupted)controlRepository.halt(String.valueOf(row.get("policyId")));}

    private void process(Map<String,Object> session){if(!"RUNNING".equals(session.get("status")))return;long tenant=((Number)session.get("tenantId")).longValue(),owner=((Number)session.get("ownerId")).longValue();String id=String.valueOf(session.get("id")),policy=String.valueOf(session.get("policyId"));
        var sample=sampling.begin(tenant,owner,id);boolean failed=true;
        try{processSample(session,tenant,owner,id,policy,sample);failed=false;}finally{sample.finish(failed);}
    }
    private void processSample(Map<String,Object> session,long tenant,long owner,String id,String policy,LiveSamplingDiagnostics.Sample sample){
        controls.requireAccount(tenant,owner,policy);var binding=controls.strategy(tenant,owner,policy);var config=(Map<String,Object>)binding.get("configuration");sample.stage(LiveSamplingDiagnostics.Stage.ORDER_RECONCILIATION);orders.reconcilePolicy(tenant,owner,policy);
        sample.stage(LiveSamplingDiagnostics.Stage.ACCOUNT);Account account=account();sample.stage(LiveSamplingDiagnostics.Stage.INVENTORY);BigDecimal sessionPosition=orders.availablePosition(tenant,owner,policy,id);sample.stage(LiveSamplingDiagnostics.Stage.OPEN_ORDERS);int exchangeOpen=pendingCount(),platformOpen=orders.accountOpenCount();BigDecimal start=decimal(session,"startEquity"),loss=start.subtract(account.totalEquity()).max(BigDecimal.ZERO);sample.stage(LiveSamplingDiagnostics.Stage.CANDLES);MarketSignal signal=evaluate(client.marketCandles(),config);sample.stage(LiveSamplingDiagnostics.Stage.VALUATION);loss=orders.sessionLoss(tenant,owner,policy,id,signal.close(),loss);BigDecimal entry=entryPrice(repository.positionFills(id));String reason=signalReason(signal,config,entry);signal=positionExit(signal,config,entry);Map<String,Object> evidence=new TreeMap<>();evidence.put("sessionId",id);evidence.put("accountId",properties.getLiveAccountId());evidence.put("exchangeName",properties.getLiveExchange());evidence.put("strategy",binding);evidence.put("equity",plain(account.totalEquity()));evidence.put("btcExposure",plain(account.btcExposure()));evidence.put("availableBtc",plain(account.availableBtc()));evidence.put("sessionPosition",plain(sessionPosition));evidence.put("exchangeOpenOrders",exchangeOpen);evidence.put("platformOpenOrders",platformOpen);evidence.put("loss",plain(loss));evidence.put("candleAt",signal.candleAt());String hash=DatasetRegistry.hash(JsonUtils.toJsonString(evidence).getBytes(StandardCharsets.UTF_8));String reconciliation=exchangeOpen==platformOpen?"PASSED":"MISMATCH";sample.stage(LiveSamplingDiagnostics.Stage.SNAPSHOT_WRITE);repository.snapshot(id,tenant,owner,account.totalEquity(),account.btcExposure(),exchangeOpen,platformOpen,loss,reconciliation,hash,null);repository.heartbeat(id,account.totalEquity(),signal.candleAt());repository.resolve(id,"RECONCILIATION_FAILURE");
        if(exchangeOpen!=platformOpen){fail(session,"ORDER_RECONCILIATION_MISMATCH","交易所挂单 "+exchangeOpen+" 与平台活动订单 "+platformOpen+" 不一致");return;}
        if(loss.compareTo(decimal(session,"maxSessionLoss"))>=0){fail(session,"SESSION_LOSS_LIMIT","会话亏损达到 "+plain(loss)+" USDT");return;}
        sample.stage(LiveSamplingDiagnostics.Stage.SIGNAL_EXECUTION);String signalHash=DatasetRegistry.hash((id+"\n"+policy+"\n"+binding.get("strategyVersionId")+"\n"+binding.get("sourceHash")+"\n1h\n"+signal.candleAt()+"\n"+signal.type()).getBytes(StandardCharsets.UTF_8));String clientId="qa"+signalHash.substring(0,26),signalId=UUID.randomUUID().toString();if(!repository.insertSignal(signalId,id,policy,tenant,owner,signal.candleAt(),signal.type(),signal.close(),signal.fast(),signal.slow(),signalHash,"NONE".equals(signal.type())?null:clientId))return;if("NONE".equals(signal.type())){repository.signalResult(signalId,"NO_ACTION",null,null,"["+reason+"] 本根已收盘 K 线无执行动作");return;}
        if("SELL".equals(signal.type())&&(sessionPosition.signum()<=0||account.availableBtc().signum()<=0)){repository.signalResult(signalId,"NO_POSITION",null,null,"["+reason+"] 本会话无已成交且可用的 BTC 仓位");return;}BigDecimal executionPrice=client.limitPrice(signal.type(),executionPrice(client.marketTicker(),signal.type()));BigDecimal amount="SELL".equals(signal.type())?sellAmount(sessionPosition,account.availableBtc(),decimal(session,"orderNotional"),executionPrice):decimal(session,"orderNotional").divide(executionPrice,8,RoundingMode.DOWN);amount=client.limitAmount(amount);if(amount.signum()==0){repository.signalResult(signalId,"NO_POSITION",null,null,"["+reason+"] 本会话可卖数量低于订单精度");return;}var decision=controls.check(tenant,owner,policy,new LiveOrderCheckRequest(clientId,signal.type(),"LIMIT",executionPrice,amount,account.btcExposure(),dailyNotional(policy),exchangeOpen));if(!"ALLOWED_OFFLINE".equals(decision.get("decision"))){repository.signalResult(signalId,"REJECTED",String.valueOf(decision.get("id")),null,"["+reason+"] "+String.valueOf(decision.get("reasonCode")));return;}try{var token=orders.issue(tenant,owner,policy,new LiveOrderTokenRequest(clientId,"CONFIRM_LIVE_ORDER","固定策略自动会话 "+id));var order=orders.execute(tenant,owner,policy,new LiveOrderExecuteRequest(String.valueOf(token.get("token"))));repository.signalResult(signalId,"ORDER_SUBMITTED",String.valueOf(decision.get("id")),String.valueOf(order.get("id")),"["+reason+"] "+String.valueOf(order.get("status")));}catch(RuntimeException e){repository.signalResult(signalId,"ORDER_FAILED",String.valueOf(decision.get("id")),null,"["+reason+"] "+safe(e.getMessage()));throw e;}
    }
    private void fail(Map<String,Object> session,String type,String message){String id=String.valueOf(session.get("id"));long tenant=((Number)session.get("tenantId")).longValue(),owner=((Number)session.get("ownerId")).longValue();String policy=String.valueOf(session.get("policyId"));repository.alert(id,tenant,owner,type,message);repository.stop(id,"RISK_STOPPED",message);try{orders.emergencyStop(tenant,owner,policy,new LiveControlStopRequest("自动停机: "+message));}catch(RuntimeException ignored){controlRepository.halt(policy);}}
    private Map<String,Object> owned(long tenant,long owner,String id){var row=repository.get(tenant,owner,id);if(row==null)throw new IllegalArgumentException("自动实盘会话不存在");return row;}
    private void bindAccount(long tenant,long owner,Map<String,Object> session){var policy=controlRepository.get(tenant,owner,String.valueOf(session.get("policyId")));if(policy==null)throw new IllegalArgumentException("Policy not found");session.put("accountId",policy.get("accountId"));session.put("exchangeName",policy.get("exchangeName"));session.put("selectedAccount",properties.getLiveAccountId().equals(policy.get("accountId"))&&properties.getLiveExchange().equals(policy.get("exchangeName")));}
    private Account account(){return account(null);}
    private Account account(BigDecimal startingBudget){Map<String,Object> response=parse(client.accountBalance());if(!"0".equals(text(response,"code")))throw new IllegalStateException("交易所账户核对失败");Map<?,?> account=firstMap(response,"data");BigDecimal total=number(account.get("totalEq")),btc=BigDecimal.ZERO,available=BigDecimal.ZERO;Object details=account.get("details");if(startingBudget!=null){if(!(details instanceof List<?> currencies))throw new IllegalArgumentException("交易所仓位明细缺失");LiveAccountBudget.requireBuyCash(currencies,startingBudget);}if(details instanceof List<?> list)for(Object value:list)if(value instanceof Map<?,?> currency&&"BTC".equals(String.valueOf(currency.get("ccy")))){btc=number(currency.get("eqUsd"));available=number(currency.get("availBal")).max(BigDecimal.ZERO);}if(total.signum()==0&&details instanceof List<?> list)for(Object value:list)if(value instanceof Map<?,?> currency)total=total.add(number(currency.get("eqUsd")));return new Account(total,btc,available);}
    private int pendingCount(){Map<String,Object> response=parse(client.pendingOrders());if(!"0".equals(text(response,"code")))throw new IllegalStateException("交易所挂单核对失败");Object data=response.get("data");return data instanceof List<?> list?list.size():Integer.MAX_VALUE;}
    private BigDecimal dailyNotional(String policy){long start=java.time.LocalDate.now(java.time.ZoneOffset.UTC).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();return orders.dailyNotional(policy,start);}
    public static MarketSignal evaluate(String json){return evaluate(json,EmaStrategyTemplate.configuration(EmaStrategyTemplate.defaults()));}
    public static MarketSignal evaluate(String json, Map<String,Object> configuration) {
        boolean breakout = "CHANNEL_BREAKOUT".equals(configuration.get("template"));
        int first = ((Number) configuration.get(breakout ? "entryPeriod" : "fastPeriod")).intValue();
        int second = ((Number) configuration.get(breakout ? "exitPeriod" : "slowPeriod")).intValue();
        if (first < 2 || first > 120 || second < 2 || second > 120 || (!breakout && first >= second))
            throw new IllegalArgumentException("Invalid strategy periods");
        Map<String,Object> response = parse(json);
        if (!"0".equals(text(response,"code"))) throw new IllegalArgumentException("Exchange candle request failed");
        if (!(response.get("data") instanceof List<?> raw)) throw new IllegalArgumentException("Missing candle data");
        List<Candle> candles = new ArrayList<>(); Set<Long> timestamps = new HashSet<>();
        for (Object value : raw) if (value instanceof List<?> row && row.size() >= 9 && "1".equals(String.valueOf(row.get(8)))) {
            long at = Long.parseLong(String.valueOf(row.get(0)));
            if (!timestamps.add(at)) throw new IllegalArgumentException("Duplicate closed candle");
            var candle = new Candle(at, new BigDecimal(String.valueOf(row.get(4))), new BigDecimal(String.valueOf(row.get(5))),
                    new BigDecimal(String.valueOf(row.get(2))), new BigDecimal(String.valueOf(row.get(3))));
            if (breakout && (candle.low().signum() <= 0 || candle.high().compareTo(candle.low()) < 0
                    || candle.close().compareTo(candle.low()) < 0 || candle.close().compareTo(candle.high()) > 0 || candle.volume().signum() < 0))
                throw new IllegalArgumentException("Invalid channel candle");
            candles.add(candle);
        }
        candles.sort(Comparator.comparingLong(Candle::at));
        int minimum = breakout ? Math.max(first, second) + 1 : second + 1;
        if (candles.size() < minimum) throw new IllegalArgumentException("Insufficient closed candles");
        int i = candles.size() - 1; var current = candles.get(i);
        if (breakout) {
            BigDecimal high = candles.get(i - first).high(), low = candles.get(i - second).low();
            for (int j = i - first; j < i; j++) high = high.max(candles.get(j).high());
            for (int j = i - second; j < i; j++) low = low.min(candles.get(j).low());
            String type = "NONE";
            if (current.volume().signum() > 0) {
                if (current.close().compareTo(high) > 0) type = "BUY";
                else if (current.close().compareTo(low) < 0) type = "SELL";
            }
            return new MarketSignal(current.at(), type, current.close(), high, low);
        }
        double[] fast = ema(candles, first), slow = ema(candles, second); int previous = i - 1;
        String type = "NONE";
        if (current.volume().signum() > 0 && fast[i] > slow[i] && fast[previous] <= slow[previous]) type = "BUY";
        else if (current.volume().signum() > 0 && fast[i] < slow[i] && fast[previous] >= slow[previous]) type = "SELL";
        return new MarketSignal(current.at(), type, current.close(), BigDecimal.valueOf(fast[i]), BigDecimal.valueOf(slow[i]));
    }
    public static String signalReason(MarketSignal signal,Map<String,Object> config,BigDecimal entry){
        if(entry!=null){
            if(signal.close().compareTo(entry.multiply(BigDecimal.ONE.subtract(new BigDecimal(String.valueOf(config.get("stopLossRatio"))))))<=0)return "STOP_LOSS";
            if(signal.close().compareTo(entry.multiply(BigDecimal.ONE.add(new BigDecimal(String.valueOf(config.get("takeProfitRatio"))))))>=0)return "TAKE_PROFIT";
            if("BUY".equals(signal.type()))return "HOLDING_POSITION";
        }
        if ("CHANNEL_BREAKOUT".equals(config.get("template")))
            return "BUY".equals(signal.type()) ? "CHANNEL_ENTRY" : "SELL".equals(signal.type()) ? "CHANNEL_EXIT" : "NO_BREAKOUT";
        return "NONE".equals(signal.type())?"NO_CROSS":"EMA_CROSS";
    }
    // Exit thresholds use closed-candle gross return, not exchange-native stops or intrabar fills.
    public static MarketSignal positionExit(MarketSignal signal,Map<String,Object> config,BigDecimal entry){
        if(entry==null)return signal;
        BigDecimal stop=new BigDecimal(String.valueOf(config.get("stopLossRatio"))),roi=new BigDecimal(String.valueOf(config.get("takeProfitRatio")));
        if(signal.close().compareTo(entry.multiply(BigDecimal.ONE.subtract(stop)))<=0||signal.close().compareTo(entry.multiply(BigDecimal.ONE.add(roi)))>=0)
            return new MarketSignal(signal.candleAt(),"SELL",signal.close(),signal.fast(),signal.slow());
        // Match the template's single position: never add another BUY while holding inventory.
        return "BUY".equals(signal.type())?new MarketSignal(signal.candleAt(),"NONE",signal.close(),signal.fast(),signal.slow()):signal;
    }
    public static BigDecimal entryPrice(List<Map<String,Object>> fills){
        BigDecimal amount=BigDecimal.ZERO,cost=BigDecimal.ZERO;
        for(var row:fills){BigDecimal qty=new BigDecimal(String.valueOf(row.get("amount")));
            if(qty.signum()<=0)continue;
            if("BUY".equals(row.get("side"))){BigDecimal price=number(row.get("price"));if(price.signum()<=0)throw new IllegalStateException("Filled entry price unavailable");amount=amount.add(qty);cost=cost.add(qty.multiply(price));}
            else if("SELL".equals(row.get("side"))){if(qty.compareTo(amount)>0)throw new IllegalStateException("Session inventory inconsistent");BigDecimal left=amount.subtract(qty);cost=left.signum()==0?BigDecimal.ZERO:cost.multiply(left).divide(amount,MathContext.DECIMAL128);amount=left;}
        }
        return amount.signum()==0?null:cost.divide(amount,MathContext.DECIMAL128);
    }
    public static BigDecimal executionPrice(String json,String side){Map<String,Object> response=parse(json);if(!"0".equals(text(response,"code")))throw new IllegalArgumentException("OKX 实时报价请求失败");Map<?,?> ticker=firstMap(response,"data");Object value=ticker.get("BUY".equals(side)?"askPx":"bidPx");BigDecimal price=number(value);if(price.signum()<=0)throw new IllegalArgumentException("OKX 实时报价无效");return price;}
    private static double[] ema(List<Candle> candles,int period){double[] out=new double[candles.size()];Arrays.fill(out,Double.NaN);double sum=0;for(int i=0;i<period;i++)sum+=candles.get(i).close().doubleValue();out[period-1]=sum/period;double alpha=2.0/(period+1);for(int i=period;i<candles.size();i++)out[i]=(candles.get(i).close().doubleValue()-out[i-1])*alpha+out[i-1];return out;}
    @SuppressWarnings("unchecked") private static Map<String,Object> parse(String json){return JsonUtils.parseObject(json,Map.class);}
    private static Map<?,?> firstMap(Map<String,Object> response,String key){Object data=response.get(key);if(!(data instanceof List<?> list)||list.isEmpty()||!(list.getFirst() instanceof Map<?,?> map))throw new IllegalStateException("OKX 响应数据不完整");return map;}
    private static String text(Map<String,Object> map,String key){Object value=map.get(key);return value==null?"":String.valueOf(value);}
    private static BigDecimal number(Object value){if(value==null||String.valueOf(value).isBlank()||"null".equals(String.valueOf(value)))return BigDecimal.ZERO;return new BigDecimal(String.valueOf(value));}
    private static BigDecimal decimal(Map<String,Object> map,String key){return new BigDecimal(String.valueOf(map.get(key)));}
    private static String plain(BigDecimal value){return value.stripTrailingZeros().toPlainString();}
    public static BigDecimal sellAmount(BigDecimal sessionPosition,BigDecimal availableBtc,BigDecimal orderNotional,BigDecimal close){if(sessionPosition==null||availableBtc==null||orderNotional==null||close==null||sessionPosition.signum()<=0||availableBtc.signum()<=0||orderNotional.signum()<=0||close.signum()<=0)return BigDecimal.ZERO.setScale(8);BigDecimal cap=orderNotional.divide(close,8,RoundingMode.DOWN);return sessionPosition.min(availableBtc).min(cap).max(BigDecimal.ZERO).setScale(8,RoundingMode.DOWN);}
    private static String safe(String value){if(value==null)return "未知错误";return value.length()>500?value.substring(0,500):value;}
    private record Candle(long at,BigDecimal close,BigDecimal volume,BigDecimal high,BigDecimal low){}
    public record MarketSignal(long candleAt,String type,BigDecimal close,BigDecimal fast,BigDecimal slow){}
    private record Account(BigDecimal totalEquity,BigDecimal btcExposure,BigDecimal availableBtc){}

}
