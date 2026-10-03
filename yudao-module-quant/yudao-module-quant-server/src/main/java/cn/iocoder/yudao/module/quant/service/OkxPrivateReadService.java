package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.LivePrivateReadRequest;
import cn.iocoder.yudao.module.quant.dal.LiveControlRepository;
import cn.iocoder.yudao.module.quant.engine.LiveTradingClient;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class OkxPrivateReadService {
    private final LiveControlRepository repository;private final LiveAdmissionService admissions;private final LiveTradingClient client;private final QuantProperties properties;
    public OkxPrivateReadService(LiveControlRepository repository,LiveAdmissionService admissions,LiveTradingClient client,QuantProperties properties){this.repository=repository;this.admissions=admissions;this.client=client;this.properties=properties;}
    public Map<String,Object> readiness(long tenant,long owner,String policyId){var policy=repository.get(tenant,owner,policyId);if(policy==null)throw new IllegalArgumentException("实盘安全策略不存在");boolean selected=properties.getLiveAccountId().equals(policy.get("accountId"))&&properties.getLiveExchange().equals(policy.get("exchangeName"));boolean real=selected&&properties.isLiveExecutionEnabled()&&client.configured()&&"ARMED_OFFLINE".equals(policy.get("status"));return Map.of("credentialConfigured",client.configured(),"privateReadAvailable",selected&&client.configured(),"realOrderAvailable",real);}
    public Map<String,Object> verify(long tenant,long owner,String policyId,LivePrivateReadRequest request){
        if(!("CONFIRM_"+properties.getLiveExchange().toUpperCase(Locale.ROOT)+"_PRIVATE_READ").equals(request.confirmation()))throw new IllegalArgumentException("交易所私有只读确认语不匹配");var policy=repository.get(tenant,owner,policyId);if(policy==null)throw new IllegalArgumentException("实盘安全策略不存在");if(!properties.getLiveAccountId().equals(policy.get("accountId"))||!properties.getLiveExchange().equals(policy.get("exchangeName")))throw new IllegalArgumentException("Private query policy belongs to a different account");var report=admissions.get(tenant,owner,(String)policy.get("admissionReportId"));if(!"DOUBLE_CONFIRMED".equals(report.get("confirmationState"))||!Objects.equals(report.get("reportHash"),policy.get("admissionReportHash")))throw new IllegalArgumentException("准入报告状态或摘要已变化");
        var json=JsonUtils.getObjectMapper().valueToTree(client.accountBalance());
        try{json=JsonUtils.getObjectMapper().readTree(json.asText());}catch(Exception e){throw new IllegalStateException("交易所私有接口响应无法解析");}
        String code=json.path("code").asText();if(!"0".equals(code))throw new IllegalStateException("交易所私有接口业务校验失败，代码 "+code);int dataCount=json.path("data").isArray()?json.path("data").size():0;repository.audit(policyId,tenant,owner,owner,properties.getLiveExchange().toUpperCase(Locale.ROOT)+"_PRIVATE_READ_VERIFIED",String.valueOf(policy.get("status")),String.valueOf(policy.get("status")),request.comment().trim());return Map.of("connected",true,"responseCode",code,"dataCount",dataCount,"ordersSent",0);
    }
}
