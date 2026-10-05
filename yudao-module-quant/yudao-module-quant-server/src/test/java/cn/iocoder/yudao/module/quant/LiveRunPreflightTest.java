package cn.iocoder.yudao.module.quant;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.dal.LiveOrderRepository;
import cn.iocoder.yudao.module.quant.dal.LiveControlRepository;
import cn.iocoder.yudao.module.quant.engine.*;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import cn.iocoder.yudao.module.quant.service.*;
import org.junit.jupiter.api.Test;
import java.math.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LiveRunPreflightTest {
    @Test void staticPlanChecksEvidenceBeforeGateCreationAndPrefersSelectedAccount(){
        var p=new QuantProperties();p.setLiveExchange("binance");p.setLiveAccountId("binance-primary");
        var admissions=new LiveAdmissionService(null){
            public Map<String,Object> get(long t,long o,String id){return Map.of("reportJson","{\"checks\":[],\"strategy\":{}}","reportHash","hash","confirmationState","DOUBLE_CONFIRMED");}
            public Map<String,Object> liveStrategy(long t,long o,Map<String,Object> report){return Map.of();}
            public String reportExchange(long t,long o,Map<String,Object> report){return "binance";}
            public void requireExchange(long t,long o,Map<String,Object> report,String exchange){if(!"binance".equals(exchange))throw new IllegalArgumentException("Wrong exchange");}
        };
        class Policies extends LiveControlRepository {
            List<Map<String,Object>> rows=List.of();Policies(){super(null);}
            public List<Map<String,Object>> list(long t,long o){return rows;}
        }
        var repo=new Policies();var credentials=new LiveCredentialProvider(){public boolean configured(){return false;}public Optional<OkxCredential> load(){throw new AssertionError("Static plan must not decrypt");}};
        var service=new LiveControlService(repo,admissions,p,credentials);
        var empty=service.runPlan(1,10,"r",null,null,null,null);assertTrue(passed(empty,"EXCHANGE_EVIDENCE_MATCH"));assertFalse(passed(empty,"EXCHANGE_ACCOUNT_MATCH"));
        repo.rows=List.of(policy("foreign","other"),policy("selected","binance-primary"));
        var selected=service.runPlan(1,10,"r",null,null,null,null);assertEquals("selected",selected.get("policyId"));assertTrue(passed(selected,"EXCHANGE_ACCOUNT_MATCH"));
        p.setLiveExchange("okx");p.setLiveAccountId("okx-primary");var mismatch=service.runPlan(1,10,"r",null,null,null,null);assertFalse(passed(mismatch,"EXCHANGE_EVIDENCE_MATCH"));assertFalse(passed(mismatch,"EXCHANGE_ACCOUNT_MATCH"));assertEquals("binance",mismatch.get("candidateExchange"));
    }
    private static Map<String,Object> policy(String id,String account){return Map.of("id",id,"accountId",account,"exchangeName","binance","admissionReportId","r","maxOrderNotional",BigDecimal.TEN,"maxDailyNotional",new BigDecimal("20"),"maxTotalExposure",new BigDecimal("20"),"status","ARMED_OFFLINE");}
    static class Fixture {
        final QuantProperties p=new QuantProperties();boolean match=true,configured=true,enabled=true;
        String free="9",pending="[]";BigDecimal used=BigDecimal.ZERO,exposure=BigDecimal.ZERO;int reads;
        final LiveTradingClient client=new LiveTradingClient(){
            public boolean configured(){return configured;}
            public String marketTicker(){reads++;return "{\"code\":\"0\",\"data\":[{\"askPx\":\"85000\"}]}";}
            public BigDecimal limitAmount(BigDecimal amount){return amount.divide(new BigDecimal("0.00001"),0,RoundingMode.DOWN).multiply(new BigDecimal("0.00001"));}
            public void preflightSpotLimitOrder(String side,String price,String amount){reads++;if(new BigDecimal(price).multiply(new BigDecimal(amount)).compareTo(new BigDecimal("5"))<0)throw new IllegalArgumentException("Binance MIN_NOTIONAL exceeds this order budget");}
            public String accountBalance(){reads++;return "{\"code\":\"0\",\"data\":[{\"details\":[{\"ccy\":\"USDT\",\"cashBal\":\"100\",\"availBal\":\""+free+"\"},{\"ccy\":\"BTC\",\"availBal\":\"0\",\"eqUsd\":\""+exposure+"\"}]}]}";}
            public String pendingOrders(){reads++;return "{\"code\":\"0\",\"data\":"+pending+"}";}
            public String placeSpotLimitOrder(String c,String side,String price,String amount){throw new AssertionError("Preflight must never submit");}
            public String getOrder(String id){throw new AssertionError("No order refresh");}
            public String cancelOrder(String id){throw new AssertionError("No cancellation");}
        };
        final LiveControlService controls=new LiveControlService(null,null,p,null){
            public Map<String,Object> runPlan(long tenant,long owner,String report,BigDecimal order,BigDecimal loss,Integer fee,Integer slip){
                assertEquals(1,tenant);assertEquals(10,owner);
                var checks=new ArrayList<Map<String,Object>>();for(String id:List.of("STRATEGY_CURRENT","DOUBLE_CONFIRMED","EXCHANGE_EVIDENCE_MATCH","EXCHANGE_ACCOUNT_MATCH"))checks.add(Map.of("id",id,"passed",match,"evidence","test"));checks.add(Map.of("id","LIVE_EXECUTION_ENABLED","passed",enabled,"evidence","test"));
                return Map.of("checks",checks,"budget",Map.of("orderNotional",order),"limits",Map.of("maxDailyNotional",new BigDecimal("20"),"maxTotalExposure",new BigDecimal("20")),"evidenceHash","old","readOnly",true);
            }
        };
        final LiveOrderRepository orders=new LiveOrderRepository(null){
            public List<Map<String,Object>> accountActive(){return List.of();}
            public BigDecimal accountDailyNotional(long since){return used;}
        };
        Map<String,Object> check(String amount){return new LiveRunPreflightService(controls,client,orders,p).check(1,10,"report",new BigDecimal(amount),null,null,null);}
    }
    static boolean passed(Map<String,Object> plan,String id){return ((List<Map<String,Object>>)plan.get("checks")).stream().anyMatch(c->id.equals(c.get("id"))&&Boolean.TRUE.equals(c.get("passed")));}
    @Test void wrongAccountAndUnconfiguredCredentialsDoNotReadExchange(){
        var f=new Fixture();f.match=false;assertEquals(false,f.check("8.5").get("readyForStartRequest"));assertEquals(0,f.reads);
        f.match=true;f.configured=false;assertEquals(false,f.check("8.5").get("readyForStartRequest"));assertEquals(0,f.reads);
    }
    @Test void budgetFiveIsRejectedAfterRoundingWithoutIncreasingAmount(){
        var plan=new Fixture().check("5");assertFalse(passed(plan,"TRADING_RULES_AND_PERMISSIONS"));assertEquals(false,plan.get("readyForStartRequest"));
        var preview=(Map<String,Object>)plan.get("orderPreview");assertEquals(0,new BigDecimal("4.25").compareTo((BigDecimal)preview.get("notional")));assertEquals(new BigDecimal("0.00005"),preview.get("amount"));
    }
    @Test void zeroAvailableCashDoesNotBorrowTotalOrReservedFunds(){
        var f=new Fixture();f.free="0";var plan=f.check("8.5");assertFalse(passed(plan,"START_CASH_BUDGET"));assertEquals(BigDecimal.ZERO,((Map<?,?>)plan.get("funds")).get("availableUsdt"));assertEquals(false,plan.get("readyForStartRequest"));
        f.free="invalid";plan=f.check("8.5");assertFalse(passed(plan,"START_CASH_BUDGET"));assertFalse(plan.containsKey("funds"));
    }
    @Test void sharedDailyLimitsExposureAndUnownedOrdersBlockReadiness(){
        var f=new Fixture();f.used=new BigDecimal("15");assertFalse(passed(f.check("8.5"),"DAILY_ORDER_BUDGET"));
        f.used=BigDecimal.ZERO;f.exposure=new BigDecimal("15");assertFalse(passed(f.check("8.5"),"EXPOSURE_BUDGET"));
        f.exposure=BigDecimal.ZERO;f.pending="[{}]";assertFalse(passed(f.check("8.5"),"ACCOUNT_ORDERS_CLEAR"));
        f.pending="null";assertFalse(passed(f.check("8.5"),"ACCOUNT_BUDGET"));
    }
    @Test void validPlanRemainsReadOnlyAndSwitchesCannotBeBypassed(){
        var f=new Fixture();var plan=f.check("8.5");assertEquals(true,plan.get("readyForStartRequest"));assertEquals(false,plan.get("fundsReserved"));assertEquals(0,plan.get("ordersSent"));assertNotEquals("old",plan.get("evidenceHash"));
        f.enabled=false;assertEquals(false,f.check("8.5").get("readyForStartRequest"));assertTrue(f.reads>0);
    }
    @Test void okxRulesRequireLiveSpotPrecisionAndReadTradeWithoutWithdrawal(){
        var mapper=JsonUtils.getObjectMapper();var instruments=mapper.readTree("{\"code\":\"0\",\"data\":[{\"instId\":\"BTC-USDT\",\"instType\":\"SPOT\",\"state\":\"live\",\"tickSz\":\"0.1\",\"lotSz\":\"0.00000001\",\"minSz\":\"0.00001\"}]}");
        var config=mapper.readTree("{\"code\":\"0\",\"data\":[{\"perm\":\"read_only,trade\"}]}");
        assertDoesNotThrow(()->OkxPrivateApiClient.validatePreflight(instruments,config,new BigDecimal("85000"),new BigDecimal("0.00008")));
        assertThrows(IllegalArgumentException.class,()->OkxPrivateApiClient.validatePreflight(instruments,config,new BigDecimal("85000.01"),new BigDecimal("0.00008")));
        assertThrows(IllegalArgumentException.class,()->OkxPrivateApiClient.validatePreflight(instruments,config,new BigDecimal("85000"),new BigDecimal("0.000001")));
        var withdraw=mapper.readTree("{\"code\":\"0\",\"data\":[{\"perm\":\"read_only,trade,withdraw\"}]}");
        assertThrows(IllegalArgumentException.class,()->OkxPrivateApiClient.validatePreflight(instruments,withdraw,new BigDecimal("85000"),new BigDecimal("0.00008")));
        assertThrows(IllegalArgumentException.class,()->OkxPrivateApiClient.validatePreflight(instruments,mapper.readTree("{\"code\":\"0\",\"data\":[{}]}"),new BigDecimal("85000"),new BigDecimal("0.00008")));
    }
}
