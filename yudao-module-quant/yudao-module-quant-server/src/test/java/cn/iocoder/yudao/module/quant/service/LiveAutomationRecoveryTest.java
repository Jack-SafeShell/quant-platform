package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.module.quant.api.backtest.LiveAlertResolveRequest;
import cn.iocoder.yudao.module.quant.dal.*;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class LiveAutomationRecoveryTest {
    static class Fixture {
        final JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        final QuantProperties props=new QuantProperties();final AtomicLong time=new AtomicLong(1_000_000);
        final LiveControlRepository policies;final LiveAutomationRepository sessions;final LiveOrderRepository orders;final LiveRunPreflightService preflight;
        int preflightCalls;List<Map<String,Object>> activeOrders=List.of();
        final Map<String,Object> policy=new HashMap<>(Map.of("id","p","accountId","binance-primary","exchangeName","binance","status","HALTED","admissionReportId","r","updatedAt",1));
        final Map<String,Object> session=new HashMap<>(Map.of("id","s","policyId","p","status","RISK_STOPPED","orderNotional",new BigDecimal("8.5"),"maxSessionLoss",new BigDecimal("5"),"updatedAt",2));
        final Map<String,Object> actual=new HashMap<>();final LiveAlertActionRepository actions;final LiveAutomationRecoveryService service;
        Fixture() throws Exception {
            props.setLiveExchange("binance");props.setLiveAccountId("binance-primary");
            jdbc.execute("CREATE TABLE quant_live_control_policy(id VARCHAR(36) PRIMARY KEY,account_id VARCHAR(64))");
            jdbc.execute("CREATE TABLE quant_live_automation_session(id VARCHAR(36) PRIMARY KEY,policy_id VARCHAR(36),tenant_id BIGINT,owner_id BIGINT,status VARCHAR(24))");
            jdbc.execute("CREATE TABLE quant_live_automation_alert(id VARCHAR(36) PRIMARY KEY,session_id VARCHAR(36),tenant_id BIGINT,owner_id BIGINT,alert_type VARCHAR(48),status VARCHAR(16),last_seen_at BIGINT,resolved_at BIGINT)");
            jdbc.execute(Files.readString(Path.of("../../sql/quant/028_live_automation_alert_action.sql")));
            jdbc.update("INSERT INTO quant_live_control_policy VALUES('p','binance-primary')");jdbc.update("INSERT INTO quant_live_automation_session VALUES('s','p',1,10,'RISK_STOPPED')");jdbc.update("INSERT INTO quant_live_automation_alert VALUES('a','s',1,10,'AUTOMATION_FAILURE','OPEN',100,NULL)");
            policies=new LiveControlRepository(null){public Map<String,Object> get(long t,long o,String id){return t==1&&o==10&&id.equals("p")?policy:null;}public Map<String,Object> getForUpdate(long t,long o,String id){return get(t,o,id);}};
            sessions=new LiveAutomationRepository(null){public Map<String,Object> get(long t,long o,String id){return t==1&&o==10&&id.equals("s")?session:null;}};
            orders=new LiveOrderRepository(null){public List<Map<String,Object>> accountActive(){return activeOrders;}};
            var checks=new ArrayList<Map<String,Object>>();for(String id:List.of("STRATEGY_CURRENT","DOUBLE_CONFIRMED","EXCHANGE_EVIDENCE_MATCH","EXCHANGE_ACCOUNT_MATCH","EXECUTION_QUOTE","TRADING_RULES_AND_PERMISSIONS","START_CASH_BUDGET","ACCOUNT_ORDERS_CLEAR","DAILY_ORDER_BUDGET","EXPOSURE_BUDGET"))checks.add(new HashMap<>(Map.of("id",id,"passed",true,"evidence","verified")));
            actual.putAll(Map.of("policyId","p","readOnly",true,"ordersSent",0,"fundsReserved",false,"evidenceHash","preflight","checks",checks));
            preflight=new LiveRunPreflightService(null,null,null,props){public Map<String,Object> check(long t,long o,String r,BigDecimal order,BigDecimal loss,Integer fee,Integer slip){assertEquals(1,t);assertEquals(10,o);assertEquals("r",r);preflightCalls++;return actual;}};
            actions=new LiveAlertActionRepository(jdbc);service=new LiveAutomationRecoveryService(actions,sessions,policies,orders,preflight,props,time::get);
        }
        LiveAlertResolveRequest request(Map<String,Object> proof){return new LiveAlertResolveRequest((String)proof.get("checkId"),(String)proof.get("evidenceHash"),"复核通过，保留偶发网络故障限制");}
        Object resolve(LiveAlertResolveRequest request){return new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).execute(status->service.resolve(1,10,"a",request));}
        long count(){return jdbc.queryForObject("SELECT COUNT(*) FROM quant_live_automation_alert_action",Long.class);}
        String status(){return jdbc.queryForObject("SELECT status FROM quant_live_automation_alert WHERE id='a'",String.class);}
    }
    @Test void checkedResolutionIsAuditedAtomicAndIdempotentWithoutActivation()throws Exception {
        var f=new Fixture();var proof=f.service.check(1,10,"a");assertEquals(true,proof.get("readyForResolution"));assertEquals(1,f.count());assertEquals("OPEN",f.status());
        var first=f.resolve(f.request(proof));assertEquals(first,f.resolve(f.request(proof)));assertEquals(2,f.count());assertEquals("RESOLVED",f.status());assertEquals("RISK_STOPPED",f.session.get("status"));assertEquals("HALTED",f.policy.get("status"));assertFalse(f.props.isLiveExecutionEnabled());
        assertEquals(2,f.service.list(1,10,"s").size());assertEquals(10L,((Number)f.service.list(1,10,"s").getFirst().get("actorId")).longValue());
    }
    @Test void ownershipAndSelectedAccountAreRequiredBeforeAnyPrivateCheck()throws Exception {
        var f=new Fixture();assertThrows(IllegalArgumentException.class,()->f.service.check(2,10,"a"));assertThrows(IllegalArgumentException.class,()->f.service.list(1,11,"s"));f.props.setLiveAccountId("okx-primary");assertThrows(IllegalArgumentException.class,()->f.service.check(1,10,"a"));assertEquals(0,f.preflightCalls);assertEquals(0,f.count());
    }
    @Test void activeExecutionSessionGateOrOrdersBlockBeforePrivateReads()throws Exception {
        var f=new Fixture();f.props.setLiveExecutionEnabled(true);assertThrows(IllegalArgumentException.class,()->f.service.check(1,10,"a"));f.props.setLiveExecutionEnabled(false);f.policy.put("status","ARMED_OFFLINE");assertThrows(IllegalArgumentException.class,()->f.service.check(1,10,"a"));f.policy.put("status","HALTED");
        f.jdbc.update("UPDATE quant_live_automation_session SET status='RUNNING'");assertThrows(IllegalArgumentException.class,()->f.service.check(1,10,"a"));f.jdbc.update("UPDATE quant_live_automation_session SET status='RISK_STOPPED'");f.activeOrders=List.of(Map.of("status","SUBMIT_UNKNOWN"));assertThrows(IllegalArgumentException.class,()->f.service.check(1,10,"a"));assertEquals(0,f.preflightCalls);
    }
    @Test void failedOrMissingExchangeEvidenceCannotResolve()throws Exception {
        var f=new Fixture();f.actual.put("checks",List.of(Map.of("id","ACCOUNT_ORDERS_CLEAR","passed",false,"evidence","unknown")));var proof=f.service.check(1,10,"a");assertEquals(false,proof.get("readyForResolution"));assertThrows(IllegalArgumentException.class,()->f.resolve(f.request(proof)));assertEquals("OPEN",f.status());assertEquals(1,f.count());
    }
    @Test void expiredFutureOrForgedProofCannotResolve()throws Exception {
        var f=new Fixture();var proof=f.service.check(1,10,"a");var request=f.request(proof);f.time.addAndGet(120_001);assertThrows(IllegalArgumentException.class,()->f.resolve(request));f.time.set(999_999);assertThrows(IllegalArgumentException.class,()->f.resolve(request));f.time.set(1_000_000);assertThrows(IllegalArgumentException.class,()->f.resolve(new LiveAlertResolveRequest(request.checkId(),"f".repeat(64),"reason")));assertEquals("OPEN",f.status());
    }
    @Test void changedAlertOrPolicyInvalidatesPreviouslyPassedProof()throws Exception {
        var f=new Fixture();var proof=f.service.check(1,10,"a");f.jdbc.update("UPDATE quant_live_automation_alert SET last_seen_at=101");assertThrows(IllegalArgumentException.class,()->f.resolve(f.request(proof)));f.jdbc.update("UPDATE quant_live_automation_alert SET last_seen_at=100");f.policy.put("updatedAt",3);assertThrows(IllegalArgumentException.class,()->f.resolve(f.request(proof)));assertEquals("OPEN",f.status());
    }
    @Test void auditInsertionFailureRollsBackAlertUpdate()throws Exception {
        var f=new Fixture();var proof=f.service.check(1,10,"a");f.jdbc.execute("ALTER TABLE quant_live_automation_alert_action ADD CONSTRAINT only_checks CHECK(action_type='CHECK')");assertThrows(RuntimeException.class,()->f.resolve(f.request(proof)));assertEquals("OPEN",f.status());assertEquals(1,f.count());
    }
    @Test void nonTransportRiskAndUnscopedProofAreRejected()throws Exception {
        var f=new Fixture();f.jdbc.update("UPDATE quant_live_automation_alert SET alert_type='SESSION_LOSS_LIMIT'");assertThrows(IllegalArgumentException.class,()->f.service.check(1,10,"a"));assertEquals(0,f.preflightCalls);
        f.jdbc.update("UPDATE quant_live_automation_alert SET alert_type='AUTOMATION_FAILURE'");assertThrows(IllegalArgumentException.class,()->f.resolve(new LiveAlertResolveRequest("unknown","a".repeat(64),"reason")));assertEquals("OPEN",f.status());
    }
}
