package cn.iocoder.yudao.module.quant;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.BacktestRequest;
import cn.iocoder.yudao.module.quant.api.backtest.DatasetDownloadRequest;
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
    String versionId;
    String parameterSetId;
    @BeforeEach void setup() throws Exception {
        properties = new QuantProperties(); properties.setWorkspace(root.toString()); properties.setEnabled(true);
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        new ResourceDatabasePopulator(new FileSystemResource("../../sql/quant/001_backtest.sql")).execute(ds);
        new ResourceDatabasePopulator(new FileSystemResource("../../sql/quant/004_dataset_download.sql")).execute(ds);
        new ResourceDatabasePopulator(new FileSystemResource("../../sql/quant/005_optimization_batch.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds); transactions = new DataSourceTransactionManager(ds);
        repository = new BacktestRepository(jdbc); datasets = new DatasetRegistry(properties);
        service = new BacktestService(repository, datasets, properties, transactions);
        writeDataset();
        versionId = (String) service.listStrategyVersions(1, 10).getFirst().get("id");
        parameterSetId = (String) service.listParameterSets(1, 10).getFirst().get("id");
    }
    void writeDataset() throws Exception {
        Path dir = root.resolve("datasets/test"); Files.createDirectories(dir);
        long begin = LocalDate.of(2025, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        List<List<Number>> rows = new ArrayList<>();
        for (int i = 0; i < 840; i++) rows.add(List.of(begin + i * 3600000L, 100, 105, 95, 101, 10));
        byte[] content = JsonUtils.toJsonByte(rows);
        Files.write(dir.resolve("BTC_USDT-1h.json"), content);
        Files.writeString(dir.resolve("manifest.json"), JsonUtils.toJsonString(Map.of("exchange", "okx", "pair", "BTC/USDT", "timeframe", "1h", "tradingMode", "spot", "sha256", DatasetRegistry.hash(content), "source", "synthetic test fixture")));
    }
    BacktestRequest request(String key) {
        return new BacktestRequest(key, versionId, parameterSetId, "test", "2025-01-11", "2025-01-13", new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("0.001"));
    }
    @Test void idempotencyAndOwnerIsolation() throws Exception {
        String id = service.create(1, 10, request("same"));
        assertEquals(id, service.create(1, 10, request("same")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM quant_strategy", Integer.class));
        assertThrows(IllegalArgumentException.class, () -> service.get(2, 10, id));
        assertThrows(IllegalArgumentException.class, () -> service.get(1, 11, id));
        assertTrue(service.list(2, 10).isEmpty());
        var changed = new BacktestRequest("same", versionId, parameterSetId, "test", "2025-01-11", "2025-01-12", new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("0.001"));
        assertThrows(IllegalArgumentException.class, () -> service.create(1, 10, changed));
        String otherVersion = (String) service.listStrategyVersions(2, 10).getFirst().get("id");
        String otherParameter = (String) service.listParameterSets(2, 10).getFirst().get("id");
        var otherRequest = new BacktestRequest("same", otherVersion, otherParameter, "test", "2025-01-11", "2025-01-13", new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("0.001"));
        assertNotEquals(id, service.create(2, 10, otherRequest));
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
        assertThrows(IllegalArgumentException.class, () -> datasets.load("test", LocalDate.of(2025,1,11), LocalDate.of(2025,2,6)));
    }
    @Test void datasetQualityReportIncludesValidAndInvalidDirectories() throws Exception {
        Files.createDirectories(root.resolve("datasets/broken"));
        var reports = datasets.list();
        var valid = reports.stream().filter(item -> item.id().equals("test")).findFirst().orElseThrow();
        var invalid = reports.stream().filter(item -> item.id().equals("broken")).findFirst().orElseThrow();
        assertEquals("VALID", valid.status());
        assertEquals(840, valid.candles());
        assertEquals(0, valid.gaps());
        assertEquals("INVALID", invalid.status());
        assertNotNull(invalid.error());
    }
    @Test void disabledFeatureAndInvalidRangeCreateNoRecords() {
        properties.setEnabled(false);
        assertThrows(IllegalArgumentException.class, () -> service.create(1, 1, request("disabled")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM quant_backtest_task", Integer.class));
    }
    @Test void strategyVersionIsExplicitImmutableAndOwnerScoped() throws Exception {
        var versions = service.listStrategyVersions(1, 10);
        assertEquals(versionId, versions.getFirst().get("id"));
        assertThrows(IllegalArgumentException.class, () -> service.create(1, 11, request("foreign-version")));
        String id = service.create(1, 10, request("versioned"));
        var task = service.get(1, 10, id);
        assertEquals(versionId, task.get("strategyVersionId"));
        assertEquals("QuantEmaBaseline", task.get("strategyName"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM quant_strategy_version", Integer.class));
    }
    @Test void parameterSetIsReusedAndMustMatchRequest() throws Exception {
        String id = service.create(1, 10, request("parameters"));
        assertEquals(parameterSetId, jdbc.queryForObject("SELECT parameter_set_id FROM quant_backtest_task WHERE id=?", String.class, id));
        var mismatched = new BacktestRequest("mismatch", versionId, parameterSetId, "test", "2025-01-11", "2025-01-13", new BigDecimal("1000"), new BigDecimal("200"), new BigDecimal("0.001"));
        assertThrows(IllegalArgumentException.class, () -> service.create(1, 10, mismatched));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM quant_parameter_set", Integer.class));
    }
    @Test void comparesOnlyOwnedSuccessfulExperiments() throws Exception {
        String first = service.create(1, 10, request("compare-1"));
        String second = service.create(1, 10, request("compare-2"));
        for (String id : List.of(first, second)) {
            assertTrue(repository.claim(id));
            repository.complete(id, new BacktestEngine.Output("test", "{\"totalTrades\":4,\"netProfit\":1.5,\"returnRatio\":0.0015,\"maxDrawdownRatio\":0.002}", "hash-" + id));
        }
        var comparison = service.compare(1, 10, List.of(first, second));
        assertEquals(2, comparison.size());
        assertEquals(4, comparison.getFirst().get("totalTrades"));
        assertThrows(IllegalArgumentException.class, () -> service.compare(1, 11, List.of(first, second)));
        assertThrows(IllegalArgumentException.class, () -> service.compare(1, 10, List.of(first)));
    }
    @Test void resultAndStateCommitAtomically() throws Exception {
        String id = service.create(1, 10, request("tx"));
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(s -> repository.complete(id, new BacktestEngine.Output("test", "{}", "hash"))));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM quant_backtest_result", Integer.class));
        assertEquals("QUEUED", service.get(1, 10, id).get("status"));
    }
    @Test void exportsReadableReportAndReproducibilityManifest() throws Exception {
        String id = service.create(1, 10, request("report"));
        assertTrue(repository.claim(id));
        repository.complete(id, new BacktestEngine.Output("freqtrade-test", "{\"totalTrades\":4,\"netProfit\":1.5,\"returnRatio\":0.0015,\"maxDrawdownRatio\":0.002}", "artifact-hash"));
        String markdown = new String(service.exportReport(1, 10, id, "md").content(), java.nio.charset.StandardCharsets.UTF_8);
        String manifest = new String(service.exportReport(1, 10, id, "json").content(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(markdown.contains("历史回测实验报告"));
        assertTrue(markdown.contains("artifact-hash"));
        assertTrue(manifest.contains("quant-backtest-report/v1"));
        assertTrue(manifest.contains("manifestSha256"));
        assertFalse(manifest.contains("sourceCode"));
        assertThrows(IllegalArgumentException.class, () -> service.exportReport(1, 11, id, "json"));
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
    @Test void datasetDownloadIsControlledIdempotentAndAudited() {
        var downloadRepository = new cn.iocoder.yudao.module.quant.dal.DatasetDownloadRepository(jdbc);
        var downloads = new DatasetDownloadService(downloadRepository, properties, transactions);
        var request = new DatasetDownloadRequest("download-key", "okx-btc-test", "2025-01-01", "2025-01-03");
        String id = downloads.create(1, 10, request);
        assertEquals(id, downloads.create(1, 10, request));
        assertEquals("okx", downloads.get(1, 10, id).get("exchange_name"));
        assertEquals(1, ((List<?>) downloads.get(1, 10, id).get("audits")).size());
        assertThrows(IllegalArgumentException.class, () -> downloads.get(1, 11, id));
        assertThrows(IllegalArgumentException.class, () -> downloads.create(1, 10, new DatasetDownloadRequest("download-key", "changed", "2025-01-01", "2025-01-03")));
    }
    @Test void optimizationCreatesSeparatedTrainAndValidationTasks() throws Exception {
        String second=service.createParameterSet(1,10,new cn.iocoder.yudao.module.quant.api.backtest.ParameterSetRequest(new BigDecimal("1000"),new BigDecimal("120"),new BigDecimal("0.001")));
        var optimization=new OptimizationService(new cn.iocoder.yudao.module.quant.dal.OptimizationRepository(jdbc),repository,service);
        String id=optimization.create(1,10,new cn.iocoder.yudao.module.quant.api.backtest.OptimizationRequest(versionId,"test","2025-01-11","2025-01-18","2025-01-25",List.of(parameterSetId,second)));
        var members=(List<?>)optimization.get(1,10,id).get("members");
        assertEquals(4,members.size());assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM quant_backtest_task",Integer.class));
        for(Object value:members){var member=(Map<String,Object>)value;String task=(String)member.get("taskId");assertTrue(repository.claim(task));boolean preferred=second.equals(member.get("parameterSetId"));double result="TRAIN".equals(member.get("phase"))?(preferred?0.08:0.04):(preferred?0.03:0.035);repository.complete(task,new BacktestEngine.Output("test","{\"totalTrades\":4,\"netProfit\":1,\"returnRatio\":"+result+",\"maxDrawdownRatio\":0.01}","hash-"+task));}
        var summary=optimization.get(1,10,id);assertEquals(true,summary.get("terminal"));var ranking=(List<Map<String,Object>>)summary.get("ranking");assertEquals(parameterSetId,ranking.getFirst().get("parameterSetId"));assertEquals(false,summary.get("autoSelected"));
        assertThrows(IllegalArgumentException.class,()->optimization.get(1,11,id));
    }
}
