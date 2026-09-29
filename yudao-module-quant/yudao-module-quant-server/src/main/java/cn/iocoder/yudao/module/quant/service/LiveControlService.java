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
        return repository.createOrGet(tenant,owner,reportId,(String)report.get("reportHash"),properties.getLiveRiskPolicyVersion(),properties.getLiveExchange(),properties.getLivePair(),properties.getLiveMaxOrderNotional(),properties.getLiveMaxDailyNotional(),properties.getLiveMaxTotalExposure(),properties.getLiveMaxOpenOrders());
    }

    public List<Map<String,Object>> list(long tenant,long owner){var rows=repository.list(tenant,owner);rows.forEach(this::decorate);return rows;}
    public Map<String,Object> get(long tenant,long owner,String id){var policy=repository.get(tenant,owner,id);if(policy==null)throw new IllegalArgumentException("实盘安全策略不存在");decorate(policy);policy.put("decisions",repository.decisions(tenant,owner,id));policy.put("audits",repository.audits(tenant,owner,id));return policy;}

    @Transactional public String arm(long tenant,long owner,String id,LiveControlArmRequest request){
        if(!"CONFIRM_OFFLINE_GATE_ARM".equals(request.confirmation()))throw new IllegalArgumentException("离线门禁确认语不匹配");
        var policy=repository.getForUpdate(tenant,owner,id);if(policy==null)throw new IllegalArgumentException("实盘安全策略不存在");
        if(!"HALTED".equals(policy.get("status")))throw new IllegalArgumentException("仅停机状态可启用离线门禁");
        var report=admissions.get(tenant,owner,(String)policy.get("admissionReportId"));
        if(!"DOUBLE_CONFIRMED".equals(report.get("confirmationState"))||!Objects.equals(report.get("reportHash"),policy.get("admissionReportHash")))throw new IllegalArgumentException("准入报告状态或摘要已变化");
        if(!repository.arm(id))throw new IllegalArgumentException("策略状态已变化，请刷新");
        repository.audit(id,tenant,owner,owner,"OFFLINE_GATE_ARMED","HALTED","ARMED_OFFLINE",request.comment().trim());
        return "ARMED_OFFLINE";
    }

    @Transactional public String emergencyStop(long tenant,long owner,String id,LiveControlStopRequest request){
        var policy=repository.getForUpdate(tenant,owner,id);if(policy==null)throw new IllegalArgumentException("实盘安全策略不存在");
        String from=String.valueOf(policy.get("status"));repository.halt(id);
        repository.audit(id,tenant,owner,owner,"EMERGENCY_STOP",from,"HALTED",request.comment().trim());
        return "HALTED";
    }

    @Transactional public Map<String,Object> check(long tenant,long owner,String id,LiveOrderCheckRequest request){
        var policy=repository.getForUpdate(tenant,owner,id);if(policy==null)throw new IllegalArgumentException("实盘安全策略不存在");
        Map<String,Object> canonical=new TreeMap<>();canonical.put("policyId",id);canonical.put("clientOrderId",request.clientOrderId());canonical.put("side",request.side());canonical.put("orderType",request.orderType());canonical.put("price",request.price().stripTrailingZeros().toPlainString());canonical.put("amount",request.amount().stripTrailingZeros().toPlainString());canonical.put("currentExposure",request.currentExposure().stripTrailingZeros().toPlainString());canonical.put("dailyExecutedNotional",request.dailyExecutedNotional().stripTrailingZeros().toPlainString());canonical.put("openOrders",request.openOrders());
        String hash=DatasetRegistry.hash(JsonUtils.toJsonByte(canonical));var previous=repository.decision(tenant,owner,request.clientOrderId());
        if(previous!=null){if(!Objects.equals(previous.get("requestHash"),hash))throw new IllegalArgumentException("客户端订单号已用于不同请求");return previous;}
        BigDecimal notional=request.price().multiply(request.amount());String decision="ALLOWED_OFFLINE",reason="PASSED",message="通过全部离线门禁；未发送真实订单";
        if(!"ARMED_OFFLINE".equals(policy.get("status"))){decision="REJECTED";reason="GATE_HALTED";message="全局停机开关已生效";}
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

    private void decorate(Map<String,Object> policy){policy.put("liveExecutionEnabled",properties.isLiveExecutionEnabled());policy.put("credentialProvider",credentials.configured()?"WINDOWS_DPAPI_FILE":"UNCONFIGURED");policy.put("privateApiConnected",false);policy.put("realOrderEndpointAvailable",properties.isLiveExecutionEnabled()&&credentials.configured());policy.put("activationAllowed",properties.isLiveExecutionEnabled()&&credentials.configured()&&"ARMED_OFFLINE".equals(policy.get("status")));}
    private static BigDecimal decimal(Map<String,Object> map,String key){return new BigDecimal(String.valueOf(map.get(key)));}
}
