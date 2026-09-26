package cn.iocoder.yudao.module.quant;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.BacktestRequest;
import cn.iocoder.yudao.module.quant.dal.BacktestRepository;
import cn.iocoder.yudao.module.quant.engine.*;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import cn.iocoder.yudao.module.quant.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class QuantBacktestTest {
    @TempDir Path root;
    QuantProperties properties;
    DatasetRegistry datasets;
    BacktestRepository repository;
    BacktestService service;
    DataSourceTransactionManager transactions;
    JdbcTemplate jdbc;
    @BeforeEach void setup() throws Exception {
        properties = new QuantProperties(); properties.setWorkspace(root.toString()); properties.setEnabled(true);
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        new ResourceDatabasePopulator(new FileSystemResource("../../sql/quant/001_backtest.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds); transactions = new DataSourceTransactionManager(ds);
        repository = new BacktestRepository(jdbc); datasets = new DatasetRegistry(properties);
        service = new BacktestService(repository, datasets, properties, transactions);
        writeDataset();
    }
    void writeDataset() throws Exception {
        Path dir = root.resolve("datasets/test"); Files.createDirectories(dir);
        long begin = LocalDate.of(2025, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        List<List<Number>> rows = new ArrayList<>();
        for (int i = 0; i < 288; i++) rows.add(List.of(begin + i * 3600000L, 100, 105, 95, 101, 10));
        byte[] content = JsonUtils.toJsonByte(rows);
        Files.write(dir.resolve("BTC_USDT-1h.json"), content);
        Files.writeString(dir.resolve("manifest.json"), JsonUtils.toJsonString(Map.of("exchange", "okx", "pair", "BTC/USDT", "timeframe", "1h", "tradingMode", "spot", "sha256", DatasetRegistry.hash(content), "source", "synthetic test fixture")));
    }
    static BacktestRequest request(String key) {
        return new BacktestRequest(key, "test", "2025-01-11", "2025-01-13", new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("0.001"));
    }
    @Test void idempotencyAndOwnerIsolation() throws Exception {
        String id = service.create(1, 10, request("same"));
        assertEquals(id, service.create(1, 10, request("same")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM quant_strategy", Integer.class));
        assertThrows(IllegalArgumentException.class, () -> service.get(2, 10, id));
        assertThrows(IllegalArgumentException.class, () -> service.get(1, 11, id));
        assertTrue(service.list(2, 10).isEmpty());
        var changed = new BacktestRequest("same", "test", "2025-01-11", "2025-01-12", new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("0.001"));
        assertThrows(IllegalArgumentException.class, () -> service.create(1, 10, changed));
        assertNotEquals(id, service.create(2, 10, request("same")));
    }
    @Test void durableQueueCompletesExactlyOnceAndPersistsResult() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BacktestEngine engine = new BacktestEngine() {
            public Output run(Input input) { calls.incrementAndGet(); return new Output("test", "{\"total_trades\":0}", "digest"); }
            public void stop(String id) { }
        };
        String id = service.create(1, 10, request("run"));
        // Reconstruct worker from persisted state, not an in-memory task queue.
        var worker = new BacktestWorker(repository, engine, datasets, properties, transactions);
        worker.tick(); worker.tick();
        assertEquals(1, calls.get()); assertEquals("SUCCEEDED", service.get(1, 10, id).get("status"));
        assertEquals("{\"total_trades\":0}", service.get(1, 10, id).get("resultJson"));
        assertFalse(service.get(1,10,id).containsKey("strategySource"));
        assertFalse(service.get(1,10,id).containsKey("ownerId"));
    }
    @Test void modifiedDatasetCannotRunAndFailureIsDurable() throws Exception {
        String id = service.create(1, 10, request("mutation"));
        Files.writeString(root.resolve("datasets/test/BTC_USDT-1h.json"), "[]");
        BacktestEngine engine = new BacktestEngine() {
            public Output run(Input input) { fail("Engine must not run on modified input"); return null; }
            public void stop(String id) { }
        };
        new BacktestWorker(repository, engine, datasets, properties, transactions).tick();
        assertEquals("FAILED", service.get(1, 10, id).get("status"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM quant_backtest_result", Integer.class));
    }
    @Test void incompleteCoverageAndTraversalRejected() {
        assertThrows(Exception.class, () -> datasets.load("../test", LocalDate.of(2025,1,11), LocalDate.of(2025,1,13)));
        assertThrows(IllegalArgumentException.class, () -> datasets.load("test", LocalDate.of(2025,1,10), LocalDate.of(2025,1,13)));
        assertThrows(IllegalArgumentException.class, () -> datasets.load("test", LocalDate.of(2025,1,11), LocalDate.of(2025,1,14)));
    }
    @Test void disabledFeatureAndInvalidRangeCreateNoRecords() {
        properties.setEnabled(false);
        assertThrows(IllegalArgumentException.class, () -> service.create(1, 1, request("disabled")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM quant_backtest_task", Integer.class));
    }
    @Test void resultAndStateCommitAtomically() throws Exception {
        String id = service.create(1, 10, request("tx"));
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(s -> repository.complete(id, new BacktestEngine.Output("test", "{}", "hash"))));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM quant_backtest_result", Integer.class));
        assertEquals("QUEUED", service.get(1, 10, id).get("status"));
    }
    @Test void concurrentDuplicateRequestsOnlyCreateOneExperiment() throws Exception {
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<java.util.concurrent.Future<String>>();
            for (int i=0;i<4;i++) futures.add(pool.submit(() -> service.create(1,10,request("concurrent"))));
            var ids = new HashSet<String>();
            for (var f : futures) ids.add(f.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,ids.size());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM quant_strategy",Integer.class));
        }
    }
    @Test void restartStopsInterruptedContainerWithoutRerun() throws Exception {
        String id=service.create(1,10,request("restart"));
        assertTrue(repository.claim(id)); assertFalse(repository.claim(id));
        var stopped=new ArrayList<String>();
        BacktestEngine engine=new BacktestEngine() {
            public Output run(Input input) { fail("Interrupted job must not rerun"); return null; }
            public void stop(String taskId) { stopped.add(taskId); }
        };
        var worker=new BacktestWorker(repository,engine,datasets,properties,transactions);
        try { worker.start(); } finally { worker.close(); }
        assertEquals(List.of(id),stopped);
        assertEquals("FAILED",service.get(1,10,id).get("status"));
    }
}
