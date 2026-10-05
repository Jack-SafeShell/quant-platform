package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.*;
import cn.iocoder.yudao.module.quant.dal.LiveControlRepository;
import cn.iocoder.yudao.module.quant.engine.DatasetRegistry;
import cn.iocoder.yudao.module.quant.engine.LiveCredentialProvider;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

@Service
public class LiveControlService {
    private final LiveControlRepository repository;
    private final LiveAdmissionService admissions;
    private final QuantProperties properties;
    private final LiveCredentialProvider credentials;
    public LiveControlService(LiveControlRepository repository,LiveAdmissionService admissions,QuantProperties properties,LiveCredentialProvider credentials){this.repository=repository;this.admissions=admissions;this.properties=properties;this.credentials=credentials;}

    public String create(long tenant,long owner,String reportId) {
        var report=admissions.get(tenant,owner,reportId);
        if(!"DOUBLE_CONFIRMED".equals(report.get("confirmationState"))) throw new IllegalArgumentException("准入报告尚未完成双确认");
        admissions.requireExchange(tenant,owner,report,properties.getLiveExchange());admissions.requireFixedLiveStrategy(tenant,owner,report);
        return repository.createOrGet(tenant,owner,reportId,(String)report.get("reportHash"),properties.getLiveRiskPolicyVersion(),properties.getLiveExchange(),properties.getLivePair(),properties.getLiveMaxOrderNotional(),properties.getLiveMaxDailyNotional(),properties.getLiveMaxTotalExposure(),properties.getLiveMaxOpenOrders());
    }

    @Transactional(readOnly=true)
    public Map<String,Object> portfolioBudget(long tenant,long owner,PortfolioBudgetRequest request){
        var totals=PortfolioBudgetPlan.calculate(properties,request);
        var plans=new ArrayList<Map<String,Object>>();var versions=new HashSet<String>();
        for(var allocation:request.allocations()){
            var plan=runPlan(tenant,owner,allocation.reportId(),allocation.orderNotional(),allocation.maxSessionLoss(),request.feeBps(),request.slippageBps());
            var strategy=JsonUtils.getObjectMapper().valueToTree(plan.get("strategy"));
            String version=strategy.path("strategyVersionId").asText();
            if(version.isBlank()||!versions.add(version))throw new IllegalArgumentException("Choose distinct strategy versions from v2 reports");
            var limits=JsonUtils.getObjectMapper().valueToTree(plan.get("limits"));
            if(allocation.capital().compareTo(limits.path("maxTotalExposure").decimalValue())>0
                    ||allocation.dailyNotional().compareTo(limits.path("maxDailyNotional").decimalValue())>0)
                throw new IllegalArgumentException("Allocation exceeds its bound policy limits");
            plans.add(Map.of("capital",allocation.capital(),"dailyNotional",allocation.dailyNotional(),"runPlan",plan));
        }
        var result=new TreeMap<String,Object>();result.put("totals",totals);result.put("allocations",plans);
        result.put("accountId",properties.getLiveAccountId());result.put("exchange",properties.getLiveExchange());result.put("pair",properties.getLivePair());
        result.put("budgetValid",true);result.put("readOnly",true);result.put("fundsReserved",false);
        result.put("multiStrategyExecutionSupported",true);result.put("readyForPortfolioStart",plans.stream().allMatch(a->Boolean.TRUE.equals(((Map<?,?>)a.get("runPlan")).get("readyForStartRequest"))));
        result.put("sharedInstrument",plans.size()>1);
        result.put("limitations",List.of("Allocation is a planning snapshot, not an account balance or a fund reservation",
                "Save an immutable portfolio configuration; each start rechecks admission, balances and existing sessions",
                "Readiness does not reserve funds; no strategy is started by this planning request"));
        result.put("evidenceHash",DatasetRegistry.hash(JsonUtils.toJsonString(result).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return result;
    }

    public Map<String,Object> runPlan(long tenant, long owner, String reportId, BigDecimal order, BigDecimal loss, Integer fee, Integer slip) {
        var report=admissions.get(tenant,owner,reportId);
        var manifest=JsonUtils.getObjectMapper().readTree((String)report.get("reportJson"));
        var policies=repository.list(tenant,owner).stream().filter(p->reportId.equals(p.get("admissionReportId"))).toList();
        var policy=policies.stream().filter(p->properties.getLiveAccountId().equals(p.get("accountId"))&&properties.getLiveExchange().equals(p.get("exchangeName"))).findFirst().orElse(policies.isEmpty()?null:policies.getFirst());
        BigDecimal orderCap=policy==null?properties.getLiveMaxOrderNotional():decimal(policy,"maxOrderNotional").min(properties.getLiveMaxOrderNotional());
        var budget=LiveRunBudget.resolve(properties,orderCap,order,loss,fee,slip);
        var checks=new ArrayList<Map<String,Object>>();
        for(var check:manifest.path("checks"))checks.add(Map.of("id",check.path("id").asText(),"passed",check.path("passed").asBoolean(),"evidence",check.path("evidence").asText()));
        String candidateExchange=admissions.reportExchange(tenant,owner,report);boolean exchangeMatch=false;try{admissions.requireExchange(tenant,owner,report,properties.getLiveExchange());exchangeMatch=true;}catch(IllegalArgumentException ignored){}
        boolean current=false;try{admissions.liveStrategy(tenant,owner,report);current=true;}catch(IllegalArgumentException ignored){}
        checks.add(Map.of("id","STRATEGY_CURRENT","passed",current,"evidence","报告完整且绑定源码未变"));
        checks.add(Map.of("id","DOUBLE_CONFIRMED","passed","DOUBLE_CONFIRMED".equals(report.get("confirmationState")),"evidence",report.get("confirmationState")));
        checks.add(Map.of("id","GATE_ARMED","passed",policy!=null&&"ARMED_OFFLINE".equals(policy.get("status")),"evidence",policy==null?"尚未创建门禁":policy.get("status")));
        checks.add(Map.of("id","LIVE_EXECUTION_ENABLED","passed",properties.isLiveExecutionEnabled(),"evidence","真实执行开关"));
        checks.add(Map.of("id","PRIVATE_EXCHANGE_SUPPORTED","passed",Set.of("okx","binance").contains(properties.getLiveExchange()),"evidence","OKX / Binance 现货私有适配已接入"));
        checks.add(Map.of("id","EXCHANGE_ACCOUNT_MATCH","passed",policy!=null&&properties.getLiveAccountId().equals(policy.get("accountId"))&&properties.getLiveExchange().equals(policy.get("exchangeName")),"evidence","策略固定账户归属"));
        checks.add(Map.of("id","EXCHANGE_EVIDENCE_MATCH","passed",exchangeMatch,"evidence","候选回测/模拟来源 "+candidateExchange+"；当前部署 "+properties.getLiveExchange()));
        checks.add(Map.of("id","LIVE_AUTOMATION_ENABLED","passed",properties.isLiveAutomationEnabled(),"evidence","自动执行开关"));
        checks.add(Map.of("id","CREDENTIAL_CONFIGURED","passed",credentials.configured(),"evidence","仅检查配置存在；不读取密钥或请求交易所"));
        var plan=new TreeMap<String,Object>();plan.put("reportId",reportId);plan.put("reportHash",report.get("reportHash"));plan.put("strategy",manifest.path("strategy"));
        plan.put("candidateExchange",candidateExchange);plan.put("executionAccount",Map.of("id",properties.getLiveAccountId(),"exchange",properties.getLiveExchange()));plan.put("preflightPerformed",false);
        plan.put("budget",budget.snapshot());plan.put("limits",Map.of("maxOrderNotional",orderCap,"maxSessionLoss",properties.getLiveMaxSessionLoss(),
                "maxDailyNotional",policy==null?properties.getLiveMaxDailyNotional():policy.get("maxDailyNotional"),"maxTotalExposure",policy==null?properties.getLiveMaxTotalExposure():policy.get("maxTotalExposure")));
        plan.put("checks",checks);plan.put("policyId",policy==null?null:policy.get("id"));
        plan.put("readyForStartRequest",checks.stream().allMatch(c->Boolean.TRUE.equals(c.get("passed"))));
        plan.put("costAssumptionsOnly",true);plan.put("readOnly",true);
        plan.put("evidenceHash",DatasetRegistry.hash(JsonUtils.toJsonByte(plan)));return plan;
    }
    public void requireAccount(long tenant,long owner,String id){
        var row=repository.get(tenant,owner,id);
        if(row==null||!properties.getLiveAccountId().equals(row.get("accountId"))||!properties.getLiveExchange().equals(row.get("exchangeName")))throw new IllegalArgumentException("Policy belongs to a different exchange account");
    }
    public Map<String,Object> strategy(long tenant,long owner,String id){
        var policy=repository.get(tenant,owner,id);if(policy==null)throw new IllegalArgumentException("Policy not found");
        var report=admissions.get(tenant,owner,(String)policy.get("admissionReportId"));
        if(!"DOUBLE_CONFIRMED".equals(report.get("confirmationState"))||!Objects.equals(report.get("reportHash"),policy.get("admissionReportHash")))throw new IllegalArgumentException("Policy admission changed");
        admissions.requireExchange(tenant,owner,report,String.valueOf(policy.get("exchangeName")));return admissions.liveStrategy(tenant,owner,report);
    }
    public List<Map<String,Object>> list(long tenant,long owner){var rows=repository.list(tenant,owner);rows.forEach(row->decorate(tenant,owner,row));return rows;}
    public Map<String,Object> get(long tenant,long owner,String id){var policy=repository.get(tenant,owner,id);if(policy==null)throw new IllegalArgumentException("实盘安全策略不存在");decorate(tenant,owner,policy);policy.put("decisions",repository.decisions(tenant,owner,id));policy.put("audits",repository.audits(tenant,owner,id));return policy;}

    @Transactional public String arm(long tenant,long owner,String id,LiveControlArmRequest request){
        if(!"CONFIRM_OFFLINE_GATE_ARM".equals(request.confirmation()))throw new IllegalArgumentException("离线门禁确认语不匹配");
        requireAccount(tenant,owner,id);var policy=repository.getForUpdate(tenant,owner,id);if(policy==null)throw new IllegalArgumentException("实盘安全策略不存在");
        if(!"HALTED".equals(policy.get("status")))throw new IllegalArgumentException("仅停机状态可启用离线门禁");
        var report=admissions.get(tenant,owner,(String)policy.get("admissionReportId"));
        if(!"DOUBLE_CONFIRMED".equals(report.get("confirmationState"))||!Objects.equals(report.get("reportHash"),policy.get("admissionReportHash")))throw new IllegalArgumentException("准入报告状态或摘要已变化");
        admissions.requireExchange(tenant,owner,report,properties.getLiveExchange());admissions.requireFixedLiveStrategy(tenant,owner,report);
        if(!repository.arm(id))throw new IllegalArgumentException("策略状态已变化，请刷新");
        repository.audit(id,tenant,owner,owner,"OFFLINE_GATE_ARMED","HALTED","ARMED_OFFLINE",request.comment().trim());
        return "ARMED_OFFLINE";
    }

    @Transactional public String emergencyStop(long tenant,long owner,String id,LiveControlStopRequest request){
        requireAccount(tenant,owner,id);var policy=repository.getForUpdate(tenant,owner,id);if(policy==null)throw new IllegalArgumentException("实盘安全策略不存在");
        String from=String.valueOf(policy.get("status"));repository.halt(id);
        repository.audit(id,tenant,owner,owner,"EMERGENCY_STOP",from,"HALTED",request.comment().trim());
        return "HALTED";
    }

    @Transactional public Map<String,Object> check(long tenant,long owner,String id,LiveOrderCheckRequest request){
        requireAccount(tenant,owner,id);var policy=repository.getForUpdate(tenant,owner,id);if(policy==null)throw new IllegalArgumentException("实盘安全策略不存在");
        Map<String,Object> canonical=new TreeMap<>();canonical.put("policyId",id);canonical.put("clientOrderId",request.clientOrderId());canonical.put("side",request.side());canonical.put("orderType",request.orderType());canonical.put("price",request.price().stripTrailingZeros().toPlainString());canonical.put("amount",request.amount().stripTrailingZeros().toPlainString());canonical.put("currentExposure",request.currentExposure().stripTrailingZeros().toPlainString());canonical.put("dailyExecutedNotional",request.dailyExecutedNotional().stripTrailingZeros().toPlainString());canonical.put("openOrders",request.openOrders());
        String hash=DatasetRegistry.hash(JsonUtils.toJsonByte(canonical));var previous=repository.decision(tenant,owner,request.clientOrderId());
        if(previous!=null){if(!Objects.equals(previous.get("requestHash"),hash))throw new IllegalArgumentException("客户端订单号已用于不同请求");return previous;}
        BigDecimal notional=request.price().multiply(request.amount());String decision="ALLOWED_OFFLINE",reason="PASSED",message="通过全部离线门禁；未发送真实订单";
        if(repository.ownedExit(tenant,owner,id,request.clientOrderId())&&!"SELL".equals(request.side())){decision="REJECTED";reason="EXIT_SELL_ONLY";message="原会话退出仅允许卖出";}
        else if(!"ARMED_OFFLINE".equals(policy.get("status"))&&!("HALTED".equals(policy.get("status"))&&"SELL".equals(request.side())&&repository.ownedExit(tenant,owner,id,request.clientOrderId()))){decision="REJECTED";reason="GATE_HALTED";message="全局停机开关已生效";}
        else if(notional.compareTo(decimal(policy,"maxOrderNotional"))>0){decision="REJECTED";reason="MAX_ORDER_NOTIONAL";message="超过单笔金额上限";}
        else if(request.dailyExecutedNotional().add(notional).compareTo(decimal(policy,"maxDailyNotional"))>0){decision="REJECTED";reason="MAX_DAILY_NOTIONAL";message="超过单日累计金额上限";}
        else if("BUY".equals(request.side())&&request.currentExposure().add(notional).compareTo(decimal(policy,"maxTotalExposure"))>0){decision="REJECTED";reason="MAX_TOTAL_EXPOSURE";message="超过总仓位上限";}
        else if("SELL".equals(request.side())&&request.currentExposure().signum()==0){decision="REJECTED";reason="NO_POSITION_TO_SELL";message="无可卖出现货仓位";}
        else if(request.openOrders()>=((Number)policy.get("maxOpenOrders")).intValue()){decision="REJECTED";reason="MAX_OPEN_ORDERS";message="超过最大挂单数";}
        String decisionId=UUID.randomUUID().toString();
        try{repository.decision(decisionId,id,tenant,owner,request.clientOrderId(),hash,request.side(),request.orderType(),request.price(),request.amount(),notional,request.currentExposure(),request.dailyExecutedNotional(),request.openOrders(),decision,reason,message);}catch(DuplicateKeyException e){var concurrent=repository.decision(tenant,owner,request.clientOrderId());if(concurrent!=null&&Objects.equals(concurrent.get("requestHash"),hash))return concurrent;throw new IllegalArgumentException("客户端订单号已被并发请求占用");}
        repository.audit(id,tenant,owner,owner,"ALLOWED_OFFLINE".equals(decision)?"ORDER_ALLOWED_OFFLINE":"ORDER_REJECTED",String.valueOf(policy.get("status")),String.valueOf(policy.get("status")),request.clientOrderId()+" / "+reason);
        return repository.decision(tenant,owner,request.clientOrderId());
    }

    private void decorate(long tenant,long owner,Map<String,Object> policy){boolean selected=properties.getLiveAccountId().equals(policy.get("accountId"))&&properties.getLiveExchange().equals(policy.get("exchangeName"));policy.put("selectedAccount",selected);policy.put("strategy",strategy(tenant,owner,String.valueOf(policy.get("id"))));policy.put("liveExecutionEnabled",properties.isLiveExecutionEnabled());policy.put("credentialProvider",credentials.configured()?"WINDOWS_DPAPI_FILE":"UNCONFIGURED");policy.put("privateApiConnected",false);policy.put("realOrderEndpointAvailable",selected&&Set.of("okx","binance").contains(properties.getLiveExchange())&&properties.isLiveExecutionEnabled()&&credentials.configured());policy.put("activationAllowed",selected&&Set.of("okx","binance").contains(properties.getLiveExchange())&&properties.isLiveExecutionEnabled()&&credentials.configured()&&"ARMED_OFFLINE".equals(policy.get("status")));}
    private static BigDecimal decimal(Map<String,Object> map,String key){return new BigDecimal(String.valueOf(map.get(key)));}
}
