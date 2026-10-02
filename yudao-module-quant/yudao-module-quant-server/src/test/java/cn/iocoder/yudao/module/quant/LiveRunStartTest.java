package cn.iocoder.yudao.module.quant;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.LiveAutomationStartRequest;
import cn.iocoder.yudao.module.quant.dal.*;
import cn.iocoder.yudao.module.quant.engine.LiveTradingClient;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import cn.iocoder.yudao.module.quant.service.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LiveRunStartTest {
    @Test void selectedBudgetIsPersistedAndRepeatedStartCannotChangeSnapshot() {
        var p=new QuantProperties();p.setLiveExecutionEnabled(true);p.setLiveAutomationEnabled(true);
        class Sessions extends LiveAutomationRepository {
            Map<String,Object> active;int creates;
            Sessions(){super(null);}
            public Map<String,Object> active(long t,long o,String policy){return active;}
            public Map<String,Object> get(long t,long o,String id){return new HashMap<>(active);}
            public String create(String policy,long t,long o,BigDecimal order,BigDecimal loss,BigDecimal equity){
                creates++;active=Map.of("id","session","policyId",policy,"orderNotional",order,"maxSessionLoss",loss);return "session";
            }
            public List<Map<String,Object>> signals(long t,long o,String id){return List.of();}
            public List<Map<String,Object>> snapshots(long t,long o,String id){return List.of();}
            public List<Map<String,Object>> alerts(long t,long o,String id){return List.of();}
        }
        class Policies extends LiveControlRepository {
            List<Map<String,Object>> audits=new ArrayList<>();Policies(){super(null);}
            public Map<String,Object> getForUpdate(long t,long o,String id){return Map.of("status","ARMED_OFFLINE","maxOrderNotional",BigDecimal.TEN,"admissionReportHash","a".repeat(64));}
            public void audit(String policy,long t,long o,long actor,String event,String from,String to,String message){assertTrue(message.length()<=500);audits.add(Map.of("eventType",event,"message",message));}
            public List<Map<String,Object>> audits(long t,long o,String id){return audits;}
        }
        class Client implements LiveTradingClient {
            int orders;public boolean configured(){return true;}
            public String accountBalance(){return "{\"code\":\"0\",\"data\":[{\"totalEq\":\"100\",\"details\":[]}]}";}
            public String pendingOrders(){return "";}
            public String placeSpotLimitOrder(String c,String s,String price,String amount){orders++;throw new AssertionError("Unexpected order");}
            public String getOrder(String id){throw new AssertionError();}public String cancelOrder(String id){throw new AssertionError();}
        }
        var repo=new Sessions();var policy=new Policies();var client=new Client();
        var controls=new LiveControlService(null,null,p,null){public Map<String,Object> strategy(long t,long o,String id){return Map.of();}};
        var service=new LiveAutomationService(repo,policy,controls,null,client,p);
        var request=new LiveAutomationStartRequest("CONFIRM_AUTO_LIVE_START","test",new BigDecimal("3"),new BigDecimal("2"),17,8);
        assertEquals("session",service.start(1,10,"policy",request));
        assertEquals(new BigDecimal("3"),repo.active.get("orderNotional"));assertEquals(new BigDecimal("2"),repo.active.get("maxSessionLoss"));
        var config=JsonUtils.getObjectMapper().readTree(String.valueOf(policy.audits.get(1).get("message")));
        assertEquals(17,config.path("feeBps").asInt());assertEquals(8,config.path("slippageBps").asInt());
        assertEquals("session",service.start(1,10,"policy",request));assertEquals(1,repo.creates);assertEquals(0,client.orders);
        assertThrows(IllegalArgumentException.class,()->service.start(1,10,"policy",new LiveAutomationStartRequest("CONFIRM_AUTO_LIVE_START","changed",new BigDecimal("4"),new BigDecimal("2"),17,8)));
        assertThrows(IllegalArgumentException.class,()->service.start(1,10,"policy",new LiveAutomationStartRequest("CONFIRM_AUTO_LIVE_START","changed",new BigDecimal("3"),new BigDecimal("2"),17,9)));
    }
}
