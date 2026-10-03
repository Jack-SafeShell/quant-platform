package cn.iocoder.yudao.module.quant;

import cn.iocoder.yudao.module.quant.service.LiveAccountBudget;
import cn.iocoder.yudao.module.quant.dal.LiveOrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LiveAccountBudgetTest {
    static BigDecimal n(String n){return new BigDecimal(n);}
    static Map<String,Object> order(String side,String amount,String filled,String status){
        var row=new HashMap<String,Object>();row.put("side",side);row.put("amount",n(amount));row.put("filledAmount",n(filled));row.put("status",status);row.put("price",n("85000"));row.put("averagePrice",n("85000"));row.put("feeAmount",n("0"));row.put("rebateAmount",n("0"));return row;
    }
    @Test void btcFeesAndPendingSellsReduceOnlyAttributedInventory(){
        var buy=order("BUY","0.00005931","0.00005931","FILLED");buy.put("feeAmount",n("-0.00000005931"));buy.put("feeCurrency","BTC");
        assertEquals(0,n("0.00005925069").compareTo(LiveAccountBudget.available(List.of(buy))));
        var sell=order("SELL","0.00002","0.00001","PARTIALLY_FILLED");
        assertEquals(0,n("0.00003925069").compareTo(LiveAccountBudget.available(List.of(buy,sell))));
        assertThrows(IllegalArgumentException.class,()->LiveAccountBudget.requireSell(List.of(buy,sell),n("0.00004")));
        sell.put("status","CANCELED");assertEquals(0,n("0.00004925069").compareTo(LiveAccountBudget.available(List.of(buy,sell))));
    }
    @Test void unknownFeesOrUnownedAccountAssetsCannotBeSold(){
        var buy=order("BUY","1","1","FILLED");buy.remove("feeAmount");
        assertEquals(BigDecimal.ZERO,LiveAccountBudget.available(List.of(buy)));
        assertThrows(IllegalArgumentException.class,()->LiveAccountBudget.requireSell(List.of(),n("0.00001")));
    }
    @Test void unknownAndSubmittingOrdersKeepExposureReserved(){
        var rows=List.of(order("BUY","0.0001","0","SUBMITTING"),order("BUY","0.0001","0.00002","SUBMIT_UNKNOWN"),order("BUY","1","0","FAILED"));
        assertEquals(0,n("15.3").compareTo(LiveAccountBudget.pendingBuys(rows)));
        var invalid=order("BUY","0.1","0.2","LIVE");assertThrows(IllegalArgumentException.class,()->LiveAccountBudget.pendingBuys(List.of(invalid)));
    }
    static JdbcTemplate database(){
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE quant_live_account_guard(id INT PRIMARY KEY)");jdbc.update("INSERT INTO quant_live_account_guard VALUES(1)");
        jdbc.execute("CREATE TABLE quant_live_exchange_order(id VARCHAR PRIMARY KEY,policy_id VARCHAR,decision_id VARCHAR,tenant_id BIGINT,owner_id BIGINT,client_order_id VARCHAR,instrument_id VARCHAR,side VARCHAR,order_type VARCHAR,price DECIMAL(20,8),amount DECIMAL(20,8),notional DECIMAL(20,8),status VARCHAR,raw_code VARCHAR,raw_message VARCHAR,filled_amount DECIMAL(20,8) DEFAULT 0,average_price DECIMAL(20,8),fee_amount DECIMAL(28,14),fee_currency VARCHAR,rebate_amount DECIMAL(28,14),rebate_currency VARCHAR,reserved_at BIGINT,submitted_at BIGINT,updated_at BIGINT,cancel_deadline_at BIGINT)");
        jdbc.execute("CREATE UNIQUE INDEX account_client ON quant_live_exchange_order(client_order_id)");
        jdbc.execute("CREATE TABLE quant_live_strategy_signal(session_id VARCHAR,policy_id VARCHAR,tenant_id BIGINT,owner_id BIGINT,client_order_id VARCHAR,exchange_order_id VARCHAR)");return jdbc;
    }
    @Test void accountReservationSerializesDifferentOwnersAndSurvivesUnknownSubmit() throws Exception {
        var jdbc=database();var repo=new LiveOrderRepository(jdbc);var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{
            var tasks=new ArrayList<Future<Boolean>>();
            for(int i=0;i<2;i++){final int owner=i;tasks.add(pool.submit(()->{start.await();return tx.execute(status->{repo.lockAccount();if(repo.accountDailyNotional(0).add(n("6")).compareTo(n("10"))>0)return false;repo.createSubmitting("o"+owner,"p"+owner,"d"+owner,1,owner,"c"+owner,"BTC-USDT","BUY","LIMIT",n("85000"),n("0.00007"),n("6"),System.currentTimeMillis()+60000);return true;});}));}
            start.countDown();int accepted=0;for(var task:tasks)if(task.get(10,TimeUnit.SECONDS))accepted++;assertEquals(1,accepted);
            var active=repo.accountActive().getFirst();String id="o"+String.valueOf(active.get("clientOrderId")).substring(1);repo.uncertain(id,"network uncertain");
            assertEquals(0,n("6").compareTo(repo.accountDailyNotional(0)));assertEquals(1,repo.accountActive().size());
            repo.failed(id,"reject","confirmed rejection");assertEquals(0,BigDecimal.ZERO.compareTo(repo.accountDailyNotional(0)));
        }finally{pool.shutdownNow();}
    }
    @Test void rejectedReservationsRollBackAndClientIdsAreAccountUnique(){
        var jdbc=database();var repo=new LiveOrderRepository(jdbc);var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        assertThrows(IllegalStateException.class,()->tx.execute(status->{repo.lockAccount();repo.createSubmitting("r","p","d",1,10,"client","BTC-USDT","BUY","LIMIT",n("85000"),n("0.0001"),n("8.5"),1);throw new IllegalStateException("abort");}));
        assertEquals(0,BigDecimal.ZERO.compareTo(repo.accountDailyNotional(0)));assertTrue(repo.accountActive().isEmpty());
        repo.createSubmitting("a","p","d",1,10,"client","BTC-USDT","BUY","LIMIT",n("85000"),n("0.0001"),n("8.5"),1);
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->repo.createSubmitting("b","p2","d2",2,20,"client","BTC-USDT","BUY","LIMIT",n("85000"),n("0.0001"),n("8.5"),1));
        assertEquals(1,repo.accountActive().size());
    }
    @Test void sessionsAndManualPoliciesNeverBorrowAnotherOwnerOrOldSession(){
        var jdbc=database();var repo=new LiveOrderRepository(jdbc);
        repo.createSubmitting("old","p","d",1,10,"old-client","BTC-USDT","BUY","LIMIT",n("85000"),n("0.0001"),n("8.5"),1);
        repo.updateCosts("old","FILLED",n("0.0001"),n("85000"),"0","",n("-0.0000001"),"BTC",n("0"),"USDT");
        jdbc.update("INSERT INTO quant_live_strategy_signal VALUES('old-session','p',1,10,'old-client','old')");
        assertEquals("old-session",repo.sessionForClient(1,10,"p","old-client"));
        assertEquals(0,n("0.0000999").compareTo(LiveAccountBudget.available(repo.inventoryRows(1,10,"p","old-session"))));
        assertTrue(repo.inventoryRows(1,10,"p","new-session").isEmpty());assertTrue(repo.inventoryRows(1,20,"p","old-session").isEmpty());assertTrue(repo.inventoryRows(1,10,"p",null).isEmpty());
        jdbc.update("INSERT INTO quant_live_strategy_signal VALUES('other-session','p',1,10,'old-client','old')");
        assertThrows(IllegalArgumentException.class,()->repo.sessionForClient(1,10,"p","old-client"));
        assertThrows(IllegalArgumentException.class,()->repo.inventoryRows(1,10,"p","old-session"));
    }
}
