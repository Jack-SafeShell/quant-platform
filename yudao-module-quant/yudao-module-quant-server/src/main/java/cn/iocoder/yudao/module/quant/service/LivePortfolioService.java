package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.*;
import cn.iocoder.yudao.module.quant.dal.*;
import cn.iocoder.yudao.module.quant.engine.*;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import java.math.*;
import java.util.*;

@Service
public class LivePortfolioService {
    private final LivePortfolioRepository repository;private final LiveOrderRepository ledger;private final LiveAutomationRepository sessions;
    private final LiveControlService controls;private final LiveAutomationService automation;private final LiveOrderService orders;private final LiveTradingClient client;
    private final LivePerformanceService performance;private final QuantProperties properties;private final TransactionTemplate transactions;
    public LivePortfolioService(LivePortfolioRepository repository,LiveOrderRepository ledger,LiveAutomationRepository sessions,LiveControlService controls,LiveAutomationService automation,LiveOrderService orders,LiveTradingClient client,LivePerformanceService performance,QuantProperties properties,PlatformTransactionManager manager){this.repository=repository;this.ledger=ledger;this.sessions=sessions;this.controls=controls;this.automation=automation;this.orders=orders;this.client=client;this.performance=performance;this.properties=properties;this.transactions=new TransactionTemplate(manager);}
    @Transactional public String create(long tenant,long owner,PortfolioBudgetRequest request){
        var plan=controls.portfolioBudget(tenant,owner,request);String id=repository.create(tenant,owner,JsonUtils.toJsonString(request),String.valueOf(plan.get("evidenceHash")),request.totalCapital(),(BigDecimal)((Map<?,?>)plan.get("totals")).get("combinedLossBudget"));
        var allocations=(List<Map<String,Object>>)plan.get("allocations");
        for(int i=0;i<request.allocations().size();i++){var allocation=request.allocations().get(i);var run=(Map<String,Object>)allocations.get(i).get("runPlan");repository.member(id,allocation.reportId(),(String)run.get("policyId"),String.valueOf(run.get("reportHash")),allocation.capital(),allocation.dailyNotional());}
        return id;
    }
    public List<Map<String,Object>> list(long tenant,long owner){return repository.list(tenant,owner);}
    public Map<String,Object> get(long tenant,long owner,String id){var row=owned(tenant,owner,id);row.put("configuration",JsonUtils.parseObject(String.valueOf(row.remove("configurationJson")),PortfolioBudgetRequest.class));var members=repository.members(id);var valuations=new ArrayList<Map<String,Object>>();boolean complete=true;
        for(var member:members)if(member.get("sessionId")!=null){var value=performance.get(tenant,owner,String.valueOf(member.get("sessionId")));member.put("performance",value);valuations.add(value);complete&=Boolean.TRUE.equals(value.get("valuationComplete"));}
        BigDecimal contribution=BigDecimal.ZERO;int filled=0;for(var value:valuations){filled+=((Number)value.get("filledOrderCount")).intValue();if(value.get("netContribution") instanceof BigDecimal net)contribution=contribution.add(net);}
        row.put("combinedNetContribution",complete?contribution:null);row.put("filledOrderCount",filled);row.put("valuationBasis","MEMBER_LAST_CLOSED_CANDLE");row.put("members",members);row.put("costsComplete",complete);row.put("combinedLoss",complete?LivePortfolioBudget.loss(valuations):null);row.put("fundsReservedOnExchange",false);return row;
    }
    @Transactional public String start(long tenant,long owner,String id,LivePortfolioActionRequest request){
        if(!"CONFIRM_PORTFOLIO_LIVE_START".equals(request.confirmation()))throw new IllegalArgumentException("Portfolio start confirmation mismatch");
        repository.lock();var row=owned(tenant,owner,id);requireAccount(row);
        if("RUNNING".equals(row.get("status"))){for(var member:repository.members(id))if(member.get("sessionId")==null||!"RUNNING".equals(sessions.get(tenant,owner,String.valueOf(member.get("sessionId"))).get("status")))throw new IllegalArgumentException("Portfolio session interrupted");return id;}
        if(!"READY".equals(row.get("status"))||!repository.active().isEmpty()||repository.otherSessions()||orders.accountOpenCount()>0)throw new IllegalArgumentException("Stop existing sessions and resolve orders before starting a portfolio");
        var config=JsonUtils.parseObject(String.valueOf(row.get("configurationJson")),PortfolioBudgetRequest.class);controls.portfolioBudget(tenant,owner,config);
        var plans=new HashMap<String,Map<String,Object>>();
        for(var allocation:config.allocations()){
            var plan=controls.runPlan(tenant,owner,allocation.reportId(),allocation.orderNotional(),allocation.maxSessionLoss(),config.feeBps(),config.slippageBps());
            var member=repository.members(id).stream().filter(m->allocation.reportId().equals(m.get("reportId"))).findFirst().orElseThrow();
            if(!Objects.equals(member.get("reportHash"),plan.get("reportHash"))||!Boolean.TRUE.equals(plan.get("readyForStartRequest")))throw new IllegalArgumentException("Portfolio admission or readiness changed: "+allocation.reportId());plans.put(allocation.reportId(),plan);
        }
        if(availableQuote().compareTo(config.totalCapital())<0)throw new IllegalArgumentException("Available USDT below portfolio capital");
        repository.running(id);
        for(var allocation:config.allocations()){
            String policy=String.valueOf(plans.get(allocation.reportId()).get("policyId"));repository.policy(id,allocation.reportId(),policy);
            String session=automation.startPortfolio(tenant,owner,policy,new LiveAutomationStartRequest("CONFIRM_AUTO_LIVE_START",request.comment(),allocation.orderNotional(),allocation.maxSessionLoss(),config.feeBps(),config.slippageBps()),id);
            repository.bind(id,allocation.reportId(),policy,session);
        }
        return id;
    }
    public String stop(long tenant,long owner,String id,String reason){requireAccount(owned(tenant,owner,id));
        var row=transactions.execute(status->{repository.lock();var value=owned(tenant,owner,id);if("RUNNING".equals(value.get("status")))repository.stopping(id,reason);return owned(tenant,owner,id);});
        if(!"STOPPING".equals(row.get("status")))return String.valueOf(row.get("status"));
        boolean settled=true;
        for(var member:repository.members(id))if(member.get("sessionId")!=null){String policy=String.valueOf(member.get("policyId"));try{automation.stop(tenant,owner,String.valueOf(member.get("sessionId")),"组合停止: "+row.get("stopReason"));orders.reconcilePolicy(tenant,owner,policy);}catch(RuntimeException e){settled=false;}if(orders.openCount(policy)>0)settled=false;}
        if(settled)repository.stopped(id,String.valueOf(row.get("stopReason")).startsWith("RISK:")?"RISK_STOPPED":"STOPPED");
        return String.valueOf(owned(tenant,owner,id).get("status"));
    }
    @Scheduled(fixedDelay=15000) public void monitor(){if(!properties.isLiveExecutionEnabled()||!properties.isLiveAutomationEnabled()||!client.configured())return;
        for(var row:repository.active()){long tenant=((Number)row.get("tenantId")).longValue(),owner=((Number)row.get("ownerId")).longValue();String id=String.valueOf(row.get("id"));
            try{if("STOPPING".equals(row.get("status"))){stop(tenant,owner,id,"继续清理挂单");continue;}
                BigDecimal mark=LiveAutomationService.executionPrice(client.marketTicker(),"SELL");var valuations=new ArrayList<Map<String,Object>>();
                for(var member:repository.members(id)){String policy=String.valueOf(member.get("policyId")),session=String.valueOf(member.get("sessionId"));var state=sessions.get(tenant,owner,session);if(state==null||!"RUNNING".equals(state.get("status")))throw new IllegalArgumentException("Member session exited");orders.reconcilePolicy(tenant,owner,policy);valuations.add(LivePerformanceService.calculate(ledger.inventoryRows(tenant,owner,policy,session),mark,null));}
                LivePortfolioBudget.requireLoss(valuations,(BigDecimal)owned(tenant,owner,id).get("lossBudget"));
            }catch(RuntimeException e){try{stop(tenant,owner,id,"RISK: "+safe(e.getMessage()));}catch(RuntimeException ignored){}}
        }
    }
    @EventListener(ApplicationReadyEvent.class) public void interrupted(){repository.interrupted();}
    public Map<String,Object> exitSession(long tenant,long owner,String sessionId,LiveSessionExitRequest request){
        if(!"CONFIRM_OWNED_SESSION_EXIT".equals(request.confirmation()))throw new IllegalArgumentException("Exit confirmation mismatch");
        var session=sessions.get(tenant,owner,sessionId);if(session==null)throw new IllegalArgumentException("Session not found");String policy=String.valueOf(session.get("policyId"));
        var previous=repository.exit(tenant,owner,sessionId,request.requestId());if(previous!=null){var existing=ledger.byClient(tenant,owner,String.valueOf(previous.get("clientOrderId")));if(existing!=null)return existing;throw new IllegalArgumentException("Previous exit prepared but not submitted; review it before using a new request ID");}
        if(!properties.isLiveExecutionEnabled()||!client.configured())throw new IllegalArgumentException("Exit execution disabled");
        controls.requireAccount(tenant,owner,policy);controls.strategy(tenant,owner,policy);orders.reconcilePolicy(tenant,owner,policy);
        var prepared=transactions.execute(status->{repository.lock();var current=sessions.get(tenant,owner,sessionId);
            if(current==null||"RUNNING".equals(current.get("status"))||!repository.active().isEmpty()||orders.accountOpenCount()>0)throw new IllegalArgumentException("Stop sessions/portfolio and resolve orders before exit");
            BigDecimal price=LiveAutomationService.executionPrice(client.marketTicker(),"SELL"),position=orders.availablePosition(tenant,owner,policy,sessionId);
            var policyRow=controls.get(tenant,owner,policy);BigDecimal cap=((BigDecimal)policyRow.get("maxOrderNotional")).min(properties.getLiveMaxOrderNotional());
            BigDecimal amount=LiveAutomationService.sellAmount(position,availableBtc(),cap,price);if(amount.signum()==0)throw new IllegalArgumentException("Owned inventory below order precision or unavailable");
            String clientId="qx"+DatasetRegistry.hash((tenant+"/"+owner+"/"+sessionId+"/"+request.requestId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0,26);
            repository.exit(clientId,tenant,owner,policy,sessionId,request.requestId(),price,amount);return Map.<String,Object>of("clientOrderId",clientId,"price",price,"amount",amount,"exposure",position.multiply(price));});
        String clientId=String.valueOf(prepared.get("clientOrderId"));var decision=controls.check(tenant,owner,policy,new LiveOrderCheckRequest(clientId,"SELL","LIMIT",(BigDecimal)prepared.get("price"),(BigDecimal)prepared.get("amount"),(BigDecimal)prepared.get("exposure"),orders.dailyNotional(policy,LiveOrderService.dayStart()),orders.accountOpenCount()));
        if(!"ALLOWED_OFFLINE".equals(decision.get("decision")))throw new IllegalArgumentException("Exit gate rejected: "+decision.get("reasonCode"));
        var token=orders.issue(tenant,owner,policy,new LiveOrderTokenRequest(clientId,"CONFIRM_LIVE_ORDER",request.comment()));
        return orders.execute(tenant,owner,policy,new LiveOrderExecuteRequest(String.valueOf(token.get("token"))));
    }
    private BigDecimal availableQuote(){return availableCurrency("USDT");}
    private BigDecimal availableBtc(){return availableCurrency("BTC");}
    private BigDecimal availableCurrency(String currency){var response=JsonUtils.getObjectMapper().readTree(client.accountBalance());if(!"0".equals(response.path("code").asText())||!response.path("data").isArray()||response.path("data").isEmpty())throw new IllegalArgumentException("Account balance unavailable");for(var detail:response.path("data").get(0).path("details"))if(currency.equals(detail.path("ccy").asText()))return new BigDecimal(detail.path("availBal").asText("0"));return BigDecimal.ZERO;}
    private void requireAccount(Map<String,Object> row){if(!properties.getLiveAccountId().equals(row.get("accountId")))throw new IllegalArgumentException("Portfolio belongs to a different exchange account");}
    private Map<String,Object> owned(long tenant,long owner,String id){var row=repository.get(tenant,owner,id);if(row==null)throw new IllegalArgumentException("Portfolio not found");return row;}
    private static String safe(String reason){return reason==null?"unknown":reason.substring(0,Math.min(400,reason.length()));}
}
