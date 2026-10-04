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
    @Test void foreignSessionCannotReadCurrentAccountOrBeMarkedStopped() {
        var p=new QuantProperties();
        class Sessions extends LiveAutomationRepository {
            int stops;Sessions(){super(null);}
            public Map<String,Object> get(long t,long o,String id){return new HashMap<>(Map.of("id",id,"policyId","binance-policy","tenantId",t,"ownerId",o,"status","RUNNING"));}
            public boolean stop(String id,String status,String reason){stops++;return true;}
        }
        var policy=new LiveControlRepository(null){public Map<String,Object> get(long t,long o,String id){return Map.of("accountId","binance-primary","exchangeName","binance");}};
        var repo=new Sessions();var controls=new LiveControlService(policy,null,p,null);
        // No client or order service: reaching any private read/write before account rejection fails this test.
        var service=new LiveAutomationService(repo,policy,controls,null,null,p);
        assertThrows(IllegalArgumentException.class,()->service.tick("foreign",1,10));
        assertThrows(IllegalArgumentException.class,()->service.stop(1,10,"foreign","test"));
        assertEquals(0,repo.stops);
    }
    @Test void selectedBudgetIsPersistedAndRepeatedStartCannotChangeSnapshot() {
        var p=new QuantProperties();p.setLiveExecutionEnabled(true);p.setLiveAutomationEnabled(true);
        p.setLiveExchange("binance");p.setLiveAccountId("binance-primary");
        class Sessions extends LiveAutomationRepository {
            Map<String,Object> active;int creates;
            Sessions(){super(null);}
            public Map<String,Object> active(long t,long o,String policy){return active;}
            public Map<String,Object> get(long t,long o,String id){return new HashMap<>(active);}
            public List<Map<String,Object>> list(long t,long o,String policy){return List.of(new HashMap<>(active));}
            public String create(String policy,long t,long o,BigDecimal order,BigDecimal loss,BigDecimal equity){
                creates++;active=Map.of("id","session","policyId",policy,"orderNotional",order,"maxSessionLoss",loss);return "session";
            }
            public List<Map<String,Object>> signals(long t,long o,String id){return List.of();}
            public List<Map<String,Object>> snapshots(long t,long o,String id){return List.of();}
            public List<Map<String,Object>> alerts(long t,long o,String id){return List.of();}
        }
        class Policies extends LiveControlRepository {
            List<Map<String,Object>> audits=new ArrayList<>();Policies(){super(null);}
            public Map<String,Object> getForUpdate(long t,long o,String id){return Map.of("status","ARMED_OFFLINE","maxOrderNotional",BigDecimal.TEN,"admissionReportHash","a".repeat(64),"accountId","binance-primary","exchangeName","binance");}
            public Map<String,Object> get(long t,long o,String id){return getForUpdate(t,o,id);}
            public void audit(String policy,long t,long o,long actor,String event,String from,String to,String message){assertTrue(message.length()<=500);audits.add(Map.of("eventType",event,"message",message));}
            public List<Map<String,Object>> audits(long t,long o,String id){return audits;}
        }
        class Client implements LiveTradingClient {
            int orders;boolean funded=true;public boolean configured(){return true;}
            public String accountBalance(){return "{\"code\":\"0\",\"data\":[{\"totalEq\":\"100\",\"details\":[{\"ccy\":\"USDT\",\"cashBal\":\"100\",\"availBal\":\""+(funded?"100":"0")+"\"}]}]}";}
            public String pendingOrders(){return "";}
            public String placeSpotLimitOrder(String c,String s,String price,String amount){orders++;throw new AssertionError("Unexpected order");}
            public String getOrder(String id){throw new AssertionError();}public String cancelOrder(String id){throw new AssertionError();}
        }
        var repo=new Sessions();var policy=new Policies();var client=new Client();
        var controls=new LiveControlService(null,null,p,null){public Map<String,Object> strategy(long t,long o,String id){return Map.of();}};
        var orderService=new LiveOrderService(policy,null,client,p,controls,new org.springframework.jdbc.datasource.DataSourceTransactionManager()){public void checkSessionStart(long t,long o,String policyId,String portfolio){}};
        var service=new LiveAutomationService(repo,policy,controls,orderService,client,p);
        var request=new LiveAutomationStartRequest("CONFIRM_AUTO_LIVE_START","test",new BigDecimal("3"),new BigDecimal("2"),17,8);
        assertEquals("session",service.start(1,10,"policy",request));
        assertEquals(new BigDecimal("3"),repo.active.get("orderNotional"));assertEquals(new BigDecimal("2"),repo.active.get("maxSessionLoss"));
        var config=JsonUtils.getObjectMapper().readTree(String.valueOf(policy.audits.get(1).get("message")));
        assertEquals(17,config.path("feeBps").asInt());assertEquals(8,config.path("slippageBps").asInt());
        assertEquals("binance-primary",config.path("accountId").asText());assertEquals("binance",config.path("exchangeName").asText());
        assertEquals(true,service.get(1,10,"session").get("selectedAccount"));
        p.setLiveAccountId("okx-primary");p.setLiveExchange("okx");
        var historical=service.get(1,10,"session");assertEquals("binance-primary",historical.get("accountId"));assertEquals("binance",historical.get("exchangeName"));assertEquals(false,historical.get("selectedAccount"));
        assertEquals("binance",service.list(1,10,"policy").getFirst().get("exchangeName"));
        p.setLiveAccountId("binance-primary");p.setLiveExchange("binance");
        assertEquals("session",service.start(1,10,"policy",request));assertEquals(1,repo.creates);assertEquals(0,client.orders);
        assertThrows(IllegalArgumentException.class,()->service.start(1,10,"policy",new LiveAutomationStartRequest("CONFIRM_AUTO_LIVE_START","changed",new BigDecimal("4"),new BigDecimal("2"),17,8)));
        assertThrows(IllegalArgumentException.class,()->service.start(1,10,"policy",new LiveAutomationStartRequest("CONFIRM_AUTO_LIVE_START","changed",new BigDecimal("3"),new BigDecimal("2"),17,9)));
        repo.active=null;client.funded=false;
        assertThrows(IllegalArgumentException.class,()->service.start(1,10,"policy",request));
        assertEquals(1,repo.creates);assertEquals(0,client.orders);assertEquals(2,policy.audits.size());
    }
}
