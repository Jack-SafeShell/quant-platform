package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.LiveAlertResolveRequest;
import cn.iocoder.yudao.module.quant.dal.*;
import cn.iocoder.yudao.module.quant.engine.DatasetRegistry;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongSupplier;

/** Recovery evidence only: resolution never arms a policy, restarts a session or sends an order. */
@Service
public class LiveAutomationRecoveryService {
    private static final long TTL=120_000;
    private static final List<String> REQUIRED=List.of("STRATEGY_CURRENT","DOUBLE_CONFIRMED","EXCHANGE_EVIDENCE_MATCH","EXCHANGE_ACCOUNT_MATCH","EXECUTION_QUOTE","TRADING_RULES_AND_PERMISSIONS","START_CASH_BUDGET","ACCOUNT_ORDERS_CLEAR","DAILY_ORDER_BUDGET","EXPOSURE_BUDGET");
    private final LiveAlertActionRepository actions;private final LiveAutomationRepository sessions;private final LiveControlRepository controls;private final LiveOrderRepository orders;private final LiveRunPreflightService preflight;private final QuantProperties properties;private final LongSupplier time;
    @org.springframework.beans.factory.annotation.Autowired
    public LiveAutomationRecoveryService(LiveAlertActionRepository actions,LiveAutomationRepository sessions,LiveControlRepository controls,LiveOrderRepository orders,LiveRunPreflightService preflight,QuantProperties properties){this(actions,sessions,controls,orders,preflight,properties,System::currentTimeMillis);}
    LiveAutomationRecoveryService(LiveAlertActionRepository actions,LiveAutomationRepository sessions,LiveControlRepository controls,LiveOrderRepository orders,LiveRunPreflightService preflight,QuantProperties properties,LongSupplier time){this.actions=actions;this.sessions=sessions;this.controls=controls;this.orders=orders;this.preflight=preflight;this.properties=properties;this.time=time;}

    public Map<String,Object> check(long tenant,long owner,String alertId){
        var alert=ownedAlert(tenant,owner,alertId,false);var policy=policy(tenant,owner,alert,false);var session=session(tenant,owner,alert);requireSafe(policy,session);requireOpen(alert);
        String before=stateHash(alert,policy,session);
        var result=preflight.check(tenant,owner,String.valueOf(policy.get("admissionReportId")),new BigDecimal(String.valueOf(session.get("orderNotional"))),new BigDecimal(String.valueOf(session.get("maxSessionLoss"))),null,null);
        var checks=new ArrayList<Map<String,Object>>();var actual=(List<Map<String,Object>>)result.get("checks");
        for(String required:REQUIRED){var found=actual==null?Optional.<Map<String,Object>>empty():actual.stream().filter(c->required.equals(c.get("id"))).findFirst();checks.add(Map.of("id",required,"passed",found.isPresent()&&Boolean.TRUE.equals(found.get().get("passed")),"evidence",found.map(c->String.valueOf(c.get("evidence"))).orElse("缺少复核证据")));}
        if(!Objects.equals(policy.get("id"),result.get("policyId"))||!Boolean.TRUE.equals(result.get("readOnly"))||!Integer.valueOf(0).equals(result.get("ordersSent"))||!Boolean.FALSE.equals(result.get("fundsReserved")))throw new IllegalArgumentException("恢复复核来源不匹配");
        var current=ownedAlert(tenant,owner,alertId,false);var currentPolicy=policy(tenant,owner,current,false);var currentSession=session(tenant,owner,current);requireSafe(currentPolicy,currentSession);requireOpen(current);
        if(!before.equals(stateHash(current,currentPolicy,currentSession)))throw new IllegalArgumentException("会话或告警已变化，请重新复核");
        long at=time.getAsLong();String id=UUID.randomUUID().toString();var evidence=new TreeMap<String,Object>();
        evidence.put("checkId",id);evidence.put("alertId",alertId);evidence.put("sessionId",alert.get("sessionId"));evidence.put("accountId",policy.get("accountId"));evidence.put("exchangeName",policy.get("exchangeName"));evidence.put("checkedAt",at);evidence.put("expiresAt",at+TTL);evidence.put("stateHash",before);evidence.put("preflightHash",result.get("evidenceHash"));evidence.put("checks",checks);evidence.put("readyForResolution",checks.stream().allMatch(c->Boolean.TRUE.equals(c.get("passed"))));evidence.put("activationAllowed",false);
        String json=JsonUtils.toJsonString(evidence),hash=hash(json);actions.append(id,alert,tenant,owner,"CHECK","OPEN","OPEN",null,hash,json,at,at+TTL);evidence.put("evidenceHash",hash);return evidence;
    }
    @Transactional
    public Map<String,Object> resolve(long tenant,long owner,String alertId,LiveAlertResolveRequest request){
        if(request.comment()==null||request.comment().isBlank()||request.comment().length()>500)throw new IllegalArgumentException("请填写不超过500字的处置说明");
        var initial=ownedAlert(tenant,owner,alertId,false);var policy=policy(tenant,owner,initial,true);var alert=ownedAlert(tenant,owner,alertId,true);var session=session(tenant,owner,alert);requireSafe(policy,session);
        if("RESOLVED".equals(alert.get("status"))){var previous=actions.resolution(tenant,owner,alertId,number(alert,"resolvedAt"));if(previous!=null)return resolved(previous);throw new IllegalArgumentException("该告警没有可复用的人工处置审计");}
        requireOpen(alert);var proof=actions.proof(tenant,owner,alertId,request.checkId());long at=time.getAsLong();
        if(proof==null||!Objects.equals(proof.get("evidenceHash"),request.evidenceHash()))throw new IllegalArgumentException("复核证据不存在或摘要不匹配");
        String json=String.valueOf(proof.get("evidenceJson"));var evidence=JsonUtils.getObjectMapper().readTree(json);
        if(!hash(json).equals(request.evidenceHash())||!evidence.path("readyForResolution").asBoolean()||at<evidence.path("checkedAt").asLong()||at>number(proof,"expiresAt")||!stateHash(alert,policy,session).equals(evidence.path("stateHash").asText()))throw new IllegalArgumentException("复核未通过、已过期或状态变化，请重新复核");
        String id=UUID.randomUUID().toString();var disposition=Map.of("checkId",request.checkId(),"checkEvidenceHash",request.evidenceHash(),"rootCauseFixed",false,"activationAllowed",false);String audit=JsonUtils.toJsonString(disposition);
        if(!actions.resolve(tenant,owner,alertId,number(alert,"lastSeenAt"),at))throw new IllegalArgumentException("告警已变化，请刷新");
        actions.append(id,alert,tenant,owner,"RESOLVE","OPEN","RESOLVED",request.comment().trim(),hash(audit),audit,at,null);return Map.of("actionId",id,"status","RESOLVED","activationAllowed",false);
    }
    public List<Map<String,Object>> list(long tenant,long owner,String sessionId){if(sessions.get(tenant,owner,sessionId)==null)throw new IllegalArgumentException("自动会话不存在");return actions.actions(tenant,owner,sessionId);}
    private Map<String,Object> ownedAlert(long tenant,long owner,String id,boolean lock){var row=actions.alert(tenant,owner,id,lock);if(row==null)throw new IllegalArgumentException("自动停机告警不存在");return row;}
    private Map<String,Object> policy(long tenant,long owner,Map<String,Object> alert,boolean lock){var row=lock?controls.getForUpdate(tenant,owner,String.valueOf(alert.get("policyId"))):controls.get(tenant,owner,String.valueOf(alert.get("policyId")));if(row==null)throw new IllegalArgumentException("实盘安全策略不存在");return row;}
    private Map<String,Object> session(long tenant,long owner,Map<String,Object> alert){var row=sessions.get(tenant,owner,String.valueOf(alert.get("sessionId")));if(row==null||!Objects.equals(row.get("policyId"),alert.get("policyId")))throw new IllegalArgumentException("会话归属不匹配");return row;}
    private void requireSafe(Map<String,Object> policy,Map<String,Object> session){
        if(!properties.getLiveAccountId().equals(policy.get("accountId"))||!properties.getLiveExchange().equals(policy.get("exchangeName")))throw new IllegalArgumentException("请在该告警所属账户的只读部署复核");
        if(properties.isPaperExecutionEnabled()||properties.isLiveExecutionEnabled()||properties.isLiveAutomationEnabled())throw new IllegalArgumentException("处置前须关闭三个执行开关");
        if(!"HALTED".equals(policy.get("status"))||!Set.of("STOPPED","RISK_STOPPED","FAILED").contains(String.valueOf(session.get("status")))||actions.accountRunning(properties.getLiveAccountId())!=0||!orders.accountActive().isEmpty())throw new IllegalArgumentException("处置前须停止会话、门禁并确认平台账户无活动订单");
    }
    private static void requireOpen(Map<String,Object> alert){if(!"AUTOMATION_FAILURE".equals(alert.get("alertType"))||!"OPEN".equals(alert.get("status")))throw new IllegalArgumentException("仅支持未解决的自动执行故障；其他风险告警保持原记录");}
    private static String stateHash(Map<String,Object> alert,Map<String,Object> policy,Map<String,Object> session){var state=new TreeMap<String,Object>();state.put("alertId",alert.get("id"));state.put("lastSeenAt",alert.get("lastSeenAt"));state.put("alertStatus",alert.get("status"));state.put("session",new TreeMap<>(session));state.put("policy",new TreeMap<>(policy));return hash(JsonUtils.toJsonString(state));}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static String hash(String json){return DatasetRegistry.hash(json.getBytes(StandardCharsets.UTF_8));}
    private static Map<String,Object> resolved(Map<String,Object> row){return Map.of("actionId",row.get("id"),"status","RESOLVED","activationAllowed",false);}
}
