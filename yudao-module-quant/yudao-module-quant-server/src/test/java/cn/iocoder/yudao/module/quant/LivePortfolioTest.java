package cn.iocoder.yudao.module.quant;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.*;
import cn.iocoder.yudao.module.quant.dal.*;
import cn.iocoder.yudao.module.quant.engine.LiveTradingClient;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import cn.iocoder.yudao.module.quant.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class LivePortfolioTest {
    static BigDecimal n(String s){return new BigDecimal(s);}
    static class Fixture {
        JdbcTemplate jdbc=LiveAccountBudgetTest.database();LiveOrderRepository orders=new LiveOrderRepository(jdbc);
        LivePortfolioRepository portfolios=new LivePortfolioRepository(jdbc);LiveAutomationRepository sessions=new LiveAutomationRepository(jdbc);
        QuantProperties props=new QuantProperties();DataSourceTransactionManager manager=new DataSourceTransactionManager(jdbc.getDataSource());TransactionTemplate tx=new TransactionTemplate(manager);
        boolean ready=true,failSecond=false;int starts,places;
        Fixture() throws Exception {
            jdbc.execute("CREATE TABLE quant_live_control_policy(id VARCHAR(36) PRIMARY KEY)");
            String schema=Files.readString(Path.of("../../sql/quant/021_live_automation.sql")).split("CREATE TABLE IF NOT EXISTS quant_live_strategy_signal")[0];jdbc.execute(schema);
            jdbc.execute("DROP TABLE quant_live_session_exit");
            for(String statement:Files.readString(Path.of("../../sql/quant/027_live_portfolio.sql")).split(";"))if(!statement.isBlank())jdbc.execute(statement);
            jdbc.update("INSERT INTO quant_live_control_policy VALUES('p1'),('p2')");props.setLiveExecutionEnabled(true);props.setLiveAutomationEnabled(true);
        }
        LiveTradingClient client=new LiveTradingClient(){public boolean configured(){return true;}public String accountBalance(){return "{\"code\":\"0\",\"data\":[{\"details\":[{\"ccy\":\"USDT\",\"availBal\":\"20\"}]}]}";}public String pendingOrders(){throw new AssertionError();}public String getOrder(String id){throw new AssertionError();}public String cancelOrder(String id){throw new AssertionError();}public String placeSpotLimitOrder(String c,String s,String p,String a){places++;throw new AssertionError();}};
        LiveControlService controls=new LiveControlService(null,null,props,null){
            public Map<String,Object> strategy(long t,long o,String id){return Map.of();}
            public Map<String,Object> portfolioBudget(long t,long o,PortfolioBudgetRequest r){var rows=new ArrayList<Map<String,Object>>();for(var a:r.allocations())rows.add(Map.of("runPlan",runPlan(t,o,a.reportId(),a.orderNotional(),a.maxSessionLoss(),r.feeBps(),r.slippageBps())));return Map.of("totals",PortfolioBudgetPlan.calculate(props,r),"allocations",rows,"evidenceHash","h".repeat(64));}
            public Map<String,Object> runPlan(long t,long o,String report,BigDecimal order,BigDecimal loss,Integer fee,Integer slip){return Map.of("policyId",report.equals("r1")?"p1":"p2","reportHash",report+"h","readyForStartRequest",ready);}
        };
        LiveOrderService orderService=new LiveOrderService(null,orders,client,props,controls,manager);
        LiveAutomationService automation=new LiveAutomationService(sessions,null,controls,orderService,client,props){
            public String startPortfolio(long t,long o,String policy,LiveAutomationStartRequest req,String portfolio){starts++;orderService.checkSessionStart(t,o,policy,portfolio);if(failSecond&&starts==2)throw new IllegalArgumentException("second failed");return sessions.create(policy,t,o,req.orderNotional(),req.maxSessionLoss(),n("20"));}
            public String stop(long t,long o,String session,String reason){sessions.stop(session,"STOPPED",reason);return "STOPPED";}
        };
        LivePortfolioService service(){return new LivePortfolioService(portfolios,orders,sessions,controls,automation,orderService,client,null,props,manager);}
        PortfolioBudgetRequest request(){return new PortfolioBudgetRequest(n("20"),10,5,List.of(new PortfolioBudgetRequest.Allocation("r1",n("10"),n("5"),n("2"),n("10")),new PortfolioBudgetRequest.Allocation("r2",n("10"),n("5"),n("2"),n("10"))));}
        String create(){return tx.execute(s->service().create(1,10,request()));}
        String start(String id){return tx.execute(s->service().start(1,10,id,new LivePortfolioActionRequest("CONFIRM_PORTFOLIO_LIVE_START","test")));}
    }
    @Test void configurationIsFixedAndStartBindsAllSessionsWithoutOrders() throws Exception {
        var f=new Fixture();String id=f.create();String json=String.valueOf(f.portfolios.get(1,10,id).get("configurationJson"));
        assertEquals("READY",f.portfolios.get(1,10,id).get("status"));assertNull(f.portfolios.get(2,10,id));
        assertEquals(id,f.start(id));assertEquals(2,f.starts);assertEquals(2,f.sessions.active().size());assertEquals(0,f.places);
        assertEquals(json,f.portfolios.get(1,10,id).get("configurationJson"));assertEquals(id,f.start(id));assertEquals(2,f.starts);
        assertThrows(IllegalArgumentException.class,()->f.tx.execute(s->{f.orderService.checkSessionStart(1,10,"p1",null);return null;}));
        assertEquals("STOPPED",f.service().stop(1,10,id,"test"));assertTrue(f.sessions.active().isEmpty());assertThrows(IllegalArgumentException.class,()->f.start(id));
    }
    @Test void failedReadinessAndPartialStartRollBackAllState() throws Exception {
        var f=new Fixture();String id=f.create();f.ready=false;assertThrows(IllegalArgumentException.class,()->f.start(id));assertEquals("READY",f.portfolios.get(1,10,id).get("status"));assertTrue(f.sessions.active().isEmpty());
        f.ready=true;f.failSecond=true;assertThrows(IllegalArgumentException.class,()->f.start(id));assertEquals("READY",f.portfolios.get(1,10,id).get("status"));assertTrue(f.sessions.active().isEmpty());assertTrue(f.portfolios.members(id).stream().allMatch(m->m.get("sessionId")==null));
    }
    @Test void manualOrderCannotBypassRunningPortfolioOrConsumeItsToken() throws Exception {
        var f=new Fixture();String id=f.create();f.start(id);
        f.jdbc.execute("CREATE TABLE quant_live_order_token(id VARCHAR PRIMARY KEY,policy_id VARCHAR,decision_id VARCHAR,tenant_id BIGINT,owner_id BIGINT,token_hash VARCHAR,status VARCHAR,expires_at BIGINT,created_at BIGINT,consumed_at BIGINT)");
        String raw="one-use-token",hash=cn.iocoder.yudao.module.quant.engine.DatasetRegistry.hash(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        f.orders.issueToken("token","p1","decision",1,10,hash,System.currentTimeMillis()+60000);
        var policy=new LiveControlRepository(null){public Map<String,Object> get(long t,long o,String p){return Map.of("status","ARMED_OFFLINE");}public Map<String,Object> decisionById(long t,long o,String d){return Map.of("policyId","p1","clientOrderId","manual","side","BUY","price",n("100000"),"amount",n("0.00005"),"notional",n("5"));}};
        var service=new LiveOrderService(policy,f.orders,f.client,f.props,f.controls,f.manager);
        assertThrows(IllegalArgumentException.class,()->service.execute(1,10,"p1",new LiveOrderExecuteRequest(raw)));
        assertEquals("ISSUED",f.orders.token(1,10,"p1",hash).get("status"));assertTrue(f.orders.accountActive().isEmpty());assertEquals(0,f.places);
    }
    @Test void exitBindingRejectsBuyEvenWithAnArmedPolicy() throws Exception {
        var f=new Fixture();String session=f.sessions.create("p1",1,10,n("5"),n("2"),n("20"));f.sessions.stop(session,"STOPPED","old");
        f.portfolios.exit("exit-client",1,10,"p1",session,"exit-request",n("85000"),n("0.0001"));
        f.jdbc.execute("CREATE TABLE quant_live_order_token(id VARCHAR PRIMARY KEY,policy_id VARCHAR,decision_id VARCHAR,tenant_id BIGINT,owner_id BIGINT,token_hash VARCHAR,status VARCHAR,expires_at BIGINT,created_at BIGINT,consumed_at BIGINT)");
        String raw="exit-buy-token",hash=cn.iocoder.yudao.module.quant.engine.DatasetRegistry.hash(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));f.orders.issueToken("token","p1","decision",1,10,hash,System.currentTimeMillis()+60000);
        var policy=new LiveControlRepository(null){public Map<String,Object> get(long t,long o,String p){return Map.of("status","ARMED_OFFLINE");}public Map<String,Object> decisionById(long t,long o,String d){return Map.of("policyId","p1","clientOrderId","exit-client","side","BUY","price",n("100000"),"amount",n("0.00005"),"notional",n("5"));}};
        var service=new LiveOrderService(policy,f.orders,f.client,f.props,f.controls,f.manager);
        assertThrows(IllegalArgumentException.class,()->service.execute(1,10,"p1",new LiveOrderExecuteRequest(raw)));assertEquals("ISSUED",f.orders.token(1,10,"p1",hash).get("status"));assertEquals(0,f.places);
    }
    @Test void unresolvedOrdersKeepPortfolioStoppingUntilConfirmedReleased() throws Exception {
        var f=new Fixture();String id=f.create();f.start(id);f.orders.createSubmitting("pending","p1","d",1,10,"pending-client","BTC-USDT","BUY","LIMIT",n("85000"),n("0.00005"),n("4.25"),1);f.orders.uncertain("pending","uncertain");
        var orderService=new LiveOrderService(null,f.orders,f.client,f.props,f.controls,f.manager){public void reconcilePolicy(long t,long o,String policy){}};
        var service=new LivePortfolioService(f.portfolios,f.orders,f.sessions,f.controls,f.automation,orderService,f.client,null,f.props,f.manager);
        assertEquals("STOPPING",service.stop(1,10,id,"test"));assertTrue(f.sessions.active().isEmpty());
        f.orders.failed("pending","reject","confirmed");assertEquals("STOPPED",service.stop(1,10,id,"retry"));assertEquals("test",f.portfolios.get(1,10,id).get("stopReason"));
    }
    @Test void dailyAllocationCapitalAndCombinedLossCannotBeBypassed(){
        var buy=LiveAccountBudgetTest.order("BUY","0.0001","0.0001","FILLED");
        assertThrows(IllegalArgumentException.class,()->LivePortfolioBudget.requireOrder(List.of(buy),n("85000"),n("5"),"BUY",n("10"),n("10"),n("0")));
        assertThrows(IllegalArgumentException.class,()->LivePortfolioBudget.requireOrder(List.of(),n("85000"),n("5"),"BUY",n("10"),n("10"),n("6")));
        var sell=LiveAccountBudgetTest.order("SELL","0.0001","0.0001","FILLED");sell.put("averagePrice",n("75000"));
        assertThrows(IllegalArgumentException.class,()->LivePortfolioBudget.requireOrder(List.of(buy,sell),n("85000"),n("10"),"BUY",n("10"),n("40"),n("0")));
        var positive=Map.<String,Object>of("valuationComplete",true,"netContribution",n("10"));var negative=Map.<String,Object>of("valuationComplete",true,"netContribution",n("-4"));
        assertThrows(IllegalArgumentException.class,()->LivePortfolioBudget.requireLoss(List.of(positive,negative),n("4")));
        assertThrows(IllegalArgumentException.class,()->LivePortfolioBudget.requireLoss(List.of(Map.of("valuationComplete",false)),n("4")));
    }
    @Test void explicitExitKeepsOriginalSessionInventoryAndExcludesManualPolicy() throws Exception {
        var f=new Fixture();String session=f.sessions.create("p1",1,10,n("5"),n("2"),n("20"));f.sessions.stop(session,"STOPPED","old");
        f.orders.createSubmitting("buy","p1","d",1,10,"buy-client","BTC-USDT","BUY","LIMIT",n("85000"),n("0.0001"),n("8.5"),1);f.orders.updateCosts("buy","FILLED",n("0.0001"),n("85000"),"0","",n("0"),"USDT",n("0"),"USDT");f.jdbc.update("INSERT INTO quant_live_strategy_signal VALUES(?,?,?,?,?,?)",session,"p1",1,10,"buy-client","buy");
        f.portfolios.exit("exit-client",1,10,"p1",session,"request",n("85000"),n("0.0001"));assertTrue(f.orders.ownedExit(1,10,"p1","exit-client"));assertFalse(f.orders.ownedExit(2,10,"p1","exit-client"));
        f.orders.createSubmitting("exit","p1","d2",1,10,"exit-client","BTC-USDT","SELL","LIMIT",n("85000"),n("0.0001"),n("8.5"),1);f.orders.updateCosts("exit","FILLED",n("0.0001"),n("85000"),"0","",n("0"),"USDT",n("0"),"USDT");
        assertEquals(session,f.orders.sessionForClient(1,10,"p1","exit-client"));assertEquals(0,n("0").compareTo(LiveAccountBudget.available(f.orders.inventoryRows(1,10,"p1",session))));assertTrue(f.orders.inventoryRows(1,10,"p1",null).isEmpty());
        assertThrows(IllegalArgumentException.class,()->f.service().exitSession(1,20,session,new LiveSessionExitRequest("request001","CONFIRM_OWNED_SESSION_EXIT","test")));
        var existing=f.service().exitSession(1,10,session,new LiveSessionExitRequest("request","CONFIRM_OWNED_SESSION_EXIT","test"));assertEquals("exit",existing.get("id"));assertEquals(0,f.places);
    }
}
