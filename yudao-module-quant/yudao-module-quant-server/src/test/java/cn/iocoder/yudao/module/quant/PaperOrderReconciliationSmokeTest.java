package cn.iocoder.yudao.module.quant;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.dal.PaperExecutionRepository;
import cn.iocoder.yudao.module.quant.dal.PaperOrderRepository;
import cn.iocoder.yudao.module.quant.engine.DockerPaperProcessLauncher;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import cn.iocoder.yudao.module.quant.service.PaperOrderReconciliationService;
import cn.iocoder.yudao.module.quant.service.PaperSafetyStopService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: real project MySQL, isolated Freqtrade-compatible SQLite and disposable dry-run container. */
@EnabledIfEnvironmentVariable(named = "QUANT_PAPER_ORDER_SMOKE", matches = "true")
class PaperOrderReconciliationSmokeTest {
    @Test void orderLifecycleTimeoutRecoveryAndRiskStop() throws Exception {
        String url=System.getenv("QUANT_TEST_JDBC_URL");
        assertNotNull(url);
        assertTrue(url.startsWith("jdbc:mysql://"));
        var ds=new DriverManagerDataSource(url,System.getenv("QUANT_TEST_DB_USER"),System.getenv("QUANT_TEST_DB_PASSWORD"));
        var jdbc=new JdbcTemplate(ds);var executionRepo=new PaperExecutionRepository(jdbc);var orderRepo=new PaperOrderRepository(jdbc);
        var reconciliation=new PaperOrderReconciliationService(executionRepo,orderRepo);
        String execution=UUID.randomUUID().toString(),container="quant-platform-paper-"+execution;long now=System.currentTimeMillis();
        Path work=Path.of(System.getenv("QUANT_WORKSPACE")).resolve("paper-rehearsal").resolve(execution).toAbsolutePath();Files.createDirectories(work);
        int created=jdbc.update("INSERT INTO quant_paper_execution(id,tenant_id,owner_id,session_id,readiness_snapshot_id,readiness_hash,status,container_name,created_at,updated_at) SELECT ?,tenant_id,owner_id,session_id,readiness_snapshot_id,readiness_hash,'RUNNING',?,?,? FROM quant_paper_execution WHERE status IN ('STOPPED','FAILED') ORDER BY created_at DESC LIMIT 1",execution,container,now,now);
        assertEquals(1,created,"需要至少一条既有终态模拟执行作为演练归属基线");
        var task=jdbc.queryForMap("SELECT tenant_id,owner_id FROM quant_paper_execution WHERE id=?",execution);long tenant=((Number)task.get("tenant_id")).longValue(),owner=((Number)task.get("owner_id")).longValue();
        executionRepo.audit(execution,owner,"REHEARSAL_CREATED",null,"RUNNING","订单对账与风险停机故障演练；不连接私有交易接口");
        jdbc.update("INSERT INTO quant_paper_command_preview(id,execution_id,preview_json,preview_hash,work_directory,created_at) VALUES(?,?,?,?,?,?)",UUID.randomUUID().toString(),execution,"{\"rehearsal\":true}","0".repeat(64),work.toString(),now);
        Path sqlite=work.resolve("tradesv3.dryrun.sqlite");createOrders(sqlite);

        var first=reconciliation.reconcile(tenant,owner,execution);assertEquals("PASSED",first.get("reconciliationStatus"));
        assertEquals(Set.of("OPEN","CANCELED"),statuses(reconciliation.orders(tenant,owner,execution)));assertEquals(2,orderAuditCount(jdbc,execution));
        update(sqlite,"UPDATE orders SET status='closed',ft_is_open=0,filled=1,cost=100,order_filled_date='2026-09-29 05:05:00',order_update_date='2026-09-29 05:05:00' WHERE order_id='rehearsal-open'");
        reconciliation.reconcile(tenant,owner,execution);assertEquals(Set.of("FILLED","CANCELED"),statuses(reconciliation.orders(tenant,owner,execution)));assertEquals(3,orderAuditCount(jdbc,execution));
        reconciliation.reconcile(tenant,owner,execution);assertEquals(3,orderAuditCount(jdbc,execution),"重复观测不得重复订单或状态审计");

        try(Connection lock=DriverManager.getConnection("jdbc:sqlite:"+sqlite);Statement statement=lock.createStatement()){
            statement.execute("PRAGMA locking_mode=EXCLUSIVE");statement.execute("BEGIN EXCLUSIVE");statement.execute("UPDATE orders SET price=price WHERE id=1");
            var failed=reconciliation.reconcile(tenant,owner,execution);assertEquals("FAILED",failed.get("reconciliationStatus"));assertNotNull(failed.get("errorMessage"));statement.execute("ROLLBACK");
        }
        assertEquals("PASSED",reconciliation.reconcile(tenant,owner,execution).get("reconciliationStatus"));
        update(sqlite,"DELETE FROM orders WHERE order_id='rehearsal-canceled'");jdbc.update("UPDATE quant_paper_order SET last_seen_at=0 WHERE execution_id=? AND source_order_id='rehearsal-canceled'",execution);
        var missing=reconciliation.reconcile(tenant,owner,execution);assertEquals(1,((Number)missing.get("unknownOrderCount")).intValue());assertTrue(statuses(reconciliation.orders(tenant,owner,execution)).contains("UNKNOWN"));

        var properties=new QuantProperties();startDisposableContainer(properties,container);
        try{assertTrue(new PaperSafetyStopService(executionRepo,new DockerPaperProcessLauncher(properties)).stopForRisk(tenant,owner,execution,"故障演练：模拟总暴露越界"));}
        finally{forceRemove(container);}
        assertEquals("STOPPED",executionRepo.get(tenant,owner,execution).get("status"));assertFalse(containerExists(container));
        assertTrue(executionRepo.audits(execution).stream().anyMatch(row->"RISK_STOPPED".equals(row.get("eventType"))));
        var evidence=Map.of("executionId",execution,"sourceOrders",1,"ledgerOrders",2,"unknownOrders",1,"orderAudits",orderAuditCount(jdbc,execution),"containerStopped",true,"executionStatus","STOPPED");
        Files.writeString(Path.of(System.getenv("QUANT_WORKSPACE")).resolve("paper-order-rehearsal.json"),JsonUtils.toJsonPrettyString(evidence));
        System.out.println("PAPER_ORDER_REHEARSAL execution="+execution+" status=STOPPED unknownOrders=1");
    }

    private static void createOrders(Path database)throws Exception{try(Connection connection=DriverManager.getConnection("jdbc:sqlite:"+database);Statement statement=connection.createStatement()){statement.execute("CREATE TABLE orders(id INTEGER PRIMARY KEY,ft_trade_id INTEGER,ft_order_side VARCHAR,ft_pair VARCHAR,order_id VARCHAR,status VARCHAR,order_type VARCHAR,price FLOAT,amount FLOAT,filled FLOAT,cost FLOAT,ft_is_open BOOLEAN,order_date DATETIME,order_filled_date DATETIME,order_update_date DATETIME)");statement.execute("INSERT INTO orders VALUES(1,1,'buy','BTC/USDT','rehearsal-open','open','limit',100,1,0,0,1,'2026-09-29 05:00:00',NULL,'2026-09-29 05:00:00')");statement.execute("INSERT INTO orders VALUES(2,1,'sell','BTC/USDT','rehearsal-canceled','canceled','limit',101,1,0,0,0,'2026-09-29 05:01:00',NULL,'2026-09-29 05:02:00')");}}
    private static void update(Path database,String sql)throws Exception{try(Connection connection=DriverManager.getConnection("jdbc:sqlite:"+database);Statement statement=connection.createStatement()){statement.execute(sql);}}
    private static Set<String> statuses(List<Map<String,Object>> orders){Set<String> result=new HashSet<>();for(var order:orders)result.add((String)order.get("orderStatus"));return result;}
    private static int orderAuditCount(JdbcTemplate jdbc,String execution){return jdbc.queryForObject("SELECT COUNT(*) FROM quant_paper_order_audit WHERE execution_id=?",Integer.class,execution);}
    private static void startDisposableContainer(QuantProperties properties,String name)throws Exception{var command=List.of(properties.getDockerExecutable(),"run","--rm","-d","--name",name,"--read-only","--network","none","--cpus","0.25","--memory","128m","--entrypoint","sleep",properties.getImage(),"600");Process process=new ProcessBuilder(command).redirectErrorStream(true).start();assertTrue(process.waitFor(30,TimeUnit.SECONDS));String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertEquals(0,process.exitValue(),output);assertTrue(containerExists(name));}
    private static boolean containerExists(String name)throws Exception{Process process=new ProcessBuilder("docker","ps","-a","--filter","name=^/"+name+"$","--format","{{.Names}}").redirectErrorStream(true).start();assertTrue(process.waitFor(10,TimeUnit.SECONDS));return new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim().equals(name);}
    private static void forceRemove(String name)throws Exception{Process process=new ProcessBuilder("docker","rm","-f",name).redirectErrorStream(true).start();process.waitFor(15,TimeUnit.SECONDS);}
}
