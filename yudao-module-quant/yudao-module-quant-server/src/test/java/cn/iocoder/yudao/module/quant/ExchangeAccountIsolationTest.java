package cn.iocoder.yudao.module.quant;
import cn.iocoder.yudao.module.quant.dal.*;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import cn.iocoder.yudao.module.quant.service.LiveControlService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class ExchangeAccountIsolationTest {
    static QuantProperties account(String id){var p=new QuantProperties();p.setLiveAccountId(id);return p;}
    static void register(JdbcTemplate jdbc,String id){jdbc.update("INSERT INTO quant_exchange_account(id,exchange_name,credential_file_name) VALUES(?,'okx','other.dpapi')",id);}
    static void reserve(LiveOrderRepository repo,String id,long owner){repo.createSubmitting(id,"policy","decision",1,owner,id,"BTC-USDT","BUY","LIMIT",new BigDecimal("80000"),new BigDecimal("0.0001"),new BigDecimal("8"),1);}
    @Test void sharedLimitsApplyWithinAccountAndNotAcrossAccounts(){
        var jdbc=LiveAccountBudgetTest.database();register(jdbc,"other");var a=new LiveOrderRepository(jdbc,account("okx-primary"));var b=new LiveOrderRepository(jdbc,account("other"));
        reserve(a,"a1",1);reserve(a,"a2",2);reserve(b,"b1",1);
        assertEquals(0,new BigDecimal("16").compareTo(a.accountDailyNotional(0)));assertEquals(0,new BigDecimal("8").compareTo(b.accountDailyNotional(0)));
        assertEquals(2,a.accountActive().size());assertEquals(1,b.accountActive().size());assertTrue(a.expired(100).isEmpty());jdbc.update("UPDATE quant_live_exchange_order SET status='LIVE' WHERE id='a1'");assertEquals(1,a.expired(100).size());assertTrue(b.expired(100).isEmpty());
        var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));tx.executeWithoutResult(s->{a.lockAccount();b.lockAccount();});
    }
    @Test void switchingCredentialFileCannotReuseTheOriginalLedger(){
        var jdbc=LiveAccountBudgetTest.database();var props=account("okx-primary");props.setWorkspace("D:/0000/quant-platform/.runtime/quant");props.setLiveCredentialFile("D:/0000/quant-platform/.runtime/quant/credentials/other.dpapi");
        var accounts=new ExchangeAccountRepository(jdbc,props);assertThrows(IllegalArgumentException.class,accounts::requireCredentialReference);
        props.setLiveCredentialFile("D:/0000/quant-platform/.runtime/quant/credentials/okx-live.dpapi");assertDoesNotThrow(accounts::requireCredentialReference);
    }
    @Test void physicalIdentityCannotChangeOrBeRelabeledToResetBudgets(){
        var jdbc=LiveAccountBudgetTest.database();register(jdbc,"other");var first=new ExchangeAccountRepository(jdbc,account("okx-primary"));var second=new ExchangeAccountRepository(jdbc,account("other"));
        first.bindIdentity("original-uid");first.bindIdentity("original-uid");assertThrows(IllegalArgumentException.class,()->first.bindIdentity("another-uid"));
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->second.bindIdentity("original-uid"));assertNull(second.current().get("identityHash"));
        second.bindIdentity("another-uid");
    }
    @Test void exchangeMismatchAndUnregisteredAccountsFailClosed(){
        var jdbc=LiveAccountBudgetTest.database();var p=account("okx-primary");p.setLiveExchange("binance");assertThrows(IllegalArgumentException.class,()->new ExchangeAccountRepository(jdbc,p).current());
        assertThrows(IllegalArgumentException.class,()->new ExchangeAccountRepository(jdbc,account("missing")).current());
    }
    @Test void policyExecutionRejectsForeignAccountBeforeLoadingStrategyOrCredentials(){
        var jdbc=LiveAccountBudgetTest.database();jdbc.execute("CREATE TABLE quant_live_control_policy(id VARCHAR,tenant_id BIGINT,owner_id BIGINT,account_id VARCHAR,admission_report_id VARCHAR,admission_report_hash VARCHAR,policy_version VARCHAR,exchange_name VARCHAR,pair_symbol VARCHAR,trading_mode VARCHAR,max_order_notional DECIMAL,max_daily_notional DECIMAL,max_total_exposure DECIMAL,max_open_orders INT,status VARCHAR,created_at BIGINT,updated_at BIGINT)");
        jdbc.update("INSERT INTO quant_live_control_policy(id,tenant_id,owner_id,account_id,exchange_name) VALUES('old',1,1,'okx-primary','okx')");
        var props=account("other");var service=new LiveControlService(new LiveControlRepository(jdbc,props),null,props,null);
        assertThrows(IllegalArgumentException.class,()->service.requireAccount(1,1,"old"));props.setLiveAccountId("okx-primary");assertDoesNotThrow(()->service.requireAccount(1,1,"old"));
    }
    @Test void cannotUseBinanceBacktestEvidenceToEnableAnOkxPolicy(){
        var repo=new LiveAdmissionRepository(null){public java.util.Map<String,Object> successfulBacktest(long t,long o,String id){return java.util.Map.of("exchangeName","binance");}};
        var service=new cn.iocoder.yudao.module.quant.service.LiveAdmissionService(repo);
        var report=java.util.Map.<String,Object>of("reportJson","{\"evidence\":{\"backtest\":{\"id\":\"task\"}}}");
        assertThrows(IllegalArgumentException.class,()->service.requireExchange(1,1,report,"okx"));assertDoesNotThrow(()->service.requireExchange(1,1,report,"binance"));
    }

}
