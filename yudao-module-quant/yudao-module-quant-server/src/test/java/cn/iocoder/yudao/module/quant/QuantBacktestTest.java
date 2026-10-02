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
import java.sql.*;
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
        new ResourceDatabasePopulator(new FileSystemResource("../../sql/quant/006_research_review.sql")).execute(ds);
        new ResourceDatabasePopulator(new FileSystemResource("../../sql/quant/007_paper_admission.sql")).execute(ds);
        new ResourceDatabasePopulator(new FileSystemResource("../../sql/quant/008_paper_session.sql")).execute(ds);
        new ResourceDatabasePopulator(new FileSystemResource("../../sql/quant/009_paper_readiness.sql")).execute(ds);
        new ResourceDatabasePopulator(new FileSystemResource("../../sql/quant/010_paper_execution.sql")).execute(ds);
        new ResourceDatabasePopulator(new FileSystemResource("../../sql/quant/011_paper_command_preview.sql")).execute(ds);
        new ResourceDatabasePopulator(new FileSystemResource("../../sql/quant/012_paper_start_token.sql")).execute(ds);
        new ResourceDatabasePopulator(new FileSystemResource("../../sql/quant/013_paper_observation_alert.sql")).execute(ds);
        new ResourceDatabasePopulator(new FileSystemResource("../../sql/quant/014_repeatable_paper_execution.sql")).execute(ds);
        new ResourceDatabasePopulator(new FileSystemResource("../../sql/quant/015_repeatable_paper_session.sql")).execute(ds);
        new ResourceDatabasePopulator(new FileSystemResource("../../sql/quant/016_paper_alert_workflow.sql")).execute(ds);
        new ResourceDatabasePopulator(new FileSystemResource("../../sql/quant/017_paper_order_ledger.sql")).execute(ds);
        new ResourceDatabasePopulator(new FileSystemResource("../../sql/quant/018_live_admission.sql")).execute(ds);
        new ResourceDatabasePopulator(new FileSystemResource("../../sql/quant/019_live_control.sql")).execute(ds);
        new ResourceDatabasePopulator(new FileSystemResource("../../sql/quant/020_live_order_execution.sql")).execute(ds);
        new ResourceDatabasePopulator(new FileSystemResource("../../sql/quant/021_live_automation.sql")).execute(ds);
        new ResourceDatabasePopulator(new FileSystemResource("../../sql/quant/023_strategy_experiment.sql")).execute(ds);
        new ResourceDatabasePopulator(new FileSystemResource("../../sql/quant/025_live_order_costs.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds); transactions = new DataSourceTransactionManager(ds);
        repository = new BacktestRepository(jdbc); datasets = new DatasetRegistry(properties);
        service = new BacktestService(repository, datasets, properties, transactions);
        writeDataset();
        versionId = (String) service.listStrategyVersions(1, 10).getFirst().get("id");
        parameterSetId = (String) service.listParameterSets(1, 10).getFirst().get("id");
    }
    StrategyExperimentService experiments() {
        return new StrategyExperimentService(new cn.iocoder.yudao.module.quant.dal.StrategyExperimentRepository(jdbc), repository,
                new OptimizationService(new cn.iocoder.yudao.module.quant.dal.OptimizationRepository(jdbc), repository, service), transactions);
    }
    cn.iocoder.yudao.module.quant.api.backtest.StrategyExperimentRequest experimentRequest(String key, List<String> versions, String dataset) {
        return new cn.iocoder.yudao.module.quant.api.backtest.StrategyExperimentRequest(key, versions, parameterSetId, dataset, "2025-01-11", "2025-01-18", "2025-01-25");
    }
    String secondVersion() throws Exception {
        return service.createStrategyVersion(1, 10, new cn.iocoder.yudao.module.quant.api.backtest.EmaStrategyRequest(12, 48, new BigDecimal("0.015"), new BigDecimal("0.03")));
    }
    @Test void experimentsShareConditionsAndAreIdempotentAndScoped() throws Exception {
        var experiments = experiments(); String second = secondVersion();
        var request = experimentRequest("experiment", List.of(versionId, second), "test");
        String id = experiments.create(1, 10, request);
        assertEquals(id, experiments.create(1, 10, experimentRequest("experiment", List.of(second, versionId), "test")));
        assertThrows(IllegalArgumentException.class, () -> experiments.create(1, 10, experimentRequest("experiment", List.of(second, versionId), "missing")));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM quant_backtest_task", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM quant_optimization_batch", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(DISTINCT parameter_set_id) FROM quant_backtest_task", Integer.class));
        var view = experiments.get(1, 10, id);
        assertEquals(false, view.get("terminal")); assertEquals(false, view.get("autoSelected"));
        assertFalse(view.containsKey("requestHash"));
        assertEquals(2, ((List<?>) view.get("rows")).size());
        assertTrue(experiments.list(2, 10).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> experiments.get(1, 11, id));
        assertThrows(IllegalArgumentException.class, () -> experiments.get(2, 10, id));
    }
    @Test void experimentsRollbackInvalidDataAndRejectForeignVersionsAndCapacity() throws Exception {
        var experiments = experiments(); String second = secondVersion();
        assertThrows(IllegalArgumentException.class, () -> experiments.create(1, 10, experimentRequest("missing", List.of(versionId, second), "missing")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM quant_strategy_experiment", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM quant_optimization_batch", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM quant_backtest_task", Integer.class));
        String foreign = (String) service.listStrategyVersions(2, 10).getFirst().get("id");
        assertThrows(IllegalArgumentException.class, () -> experiments.create(1, 10, experimentRequest("foreign", List.of(versionId, foreign), "test")));
        assertThrows(IllegalArgumentException.class, () -> experiments.create(1, 10, experimentRequest("duplicate", List.of(versionId, versionId), "test")));
        for (int i = 0; i < 7; i++) service.create(1, 10, request("queued-" + i));
        assertThrows(IllegalArgumentException.class, () -> experiments.create(1, 10, experimentRequest("capacity", List.of(versionId, second), "test")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM quant_strategy_experiment", Integer.class));
        assertEquals(7, jdbc.queryForObject("SELECT COUNT(*) FROM quant_backtest_task", Integer.class));
    }
    @Test @SuppressWarnings("unchecked") void experimentFailureStaysVisibleAndRankingWaitsForAllTasks() throws Exception {
        var experiments = experiments(); String second = secondVersion();
        String id = experiments.create(1, 10, experimentRequest("failed-variant", List.of(versionId, second), "test"));
        var taskIds = jdbc.queryForList("SELECT id FROM quant_backtest_task ORDER BY id", String.class);
        for (int i = 0; i < taskIds.size(); i++) {
            String task = taskIds.get(i); assertTrue(repository.claim(task));
            if (i == 0) repository.fail(task, "controlled failure");
            else repository.complete(task, new BacktestEngine.Output("test", "{\"totalTrades\":14,\"returnRatio\":0.02,\"maxDrawdownRatio\":0.01}", "hash-" + task));
            if (i < 3) {
                var view = experiments.get(1, 10, id); assertEquals(false, view.get("terminal"));
                assertTrue(((List<Map<String, Object>>) view.get("rows")).stream().noneMatch(row -> row.containsKey("rank")));
            }
        }
        var view = experiments.get(1, 10, id); assertEquals(true, view.get("terminal"));
        var rows = (List<Map<String, Object>>) view.get("rows");
        assertEquals(2, rows.size()); assertEquals(1, rows.getFirst().get("rank"));
        assertNull(rows.getLast().get("rank")); assertEquals(false, rows.getLast().get("paperEligible"));
        assertTrue("FAILED".equals(rows.getLast().get("trainStatus")) || "FAILED".equals(rows.getLast().get("validationStatus")));
    }
    @Test @SuppressWarnings("unchecked") void experimentRankingAndPaperPreparationRetainSelectedVersion() throws Exception {
        var experiments = experiments(); String second = secondVersion();
        String id = experiments.create(1, 10, experimentRequest("ranking", List.of(versionId, second), "test"));
        var optimization = new OptimizationService(new cn.iocoder.yudao.module.quant.dal.OptimizationRepository(jdbc), repository, service);
        var rows = (List<Map<String, Object>>) experiments.get(1, 10, id).get("rows");
        for (var row : rows) {
            var batch = optimization.get(1, 10, (String) row.get("batchId"));
            for (var task : (List<Map<String, Object>>) batch.get("members")) {
                String taskId = (String) task.get("taskId"); assertTrue(repository.claim(taskId));
                double profit = "TRAIN".equals(task.get("phase")) ? 0.03 : (second.equals(row.get("strategyVersionId")) ? 0.025 : 0.02);
                repository.complete(taskId, new BacktestEngine.Output("test", "{\"totalTrades\":14,\"netProfit\":1,\"returnRatio\":" + profit + ",\"maxDrawdownRatio\":0.01}", "hash-" + taskId));
            }
        }
        var view = experiments.get(1, 10, id); assertEquals(true, view.get("terminal"));
        rows = (List<Map<String, Object>>) view.get("rows"); var best = rows.getFirst();
        assertEquals(second, best.get("strategyVersionId")); assertEquals(1, best.get("rank"));
        String batchId = (String) best.get("batchId"); var batch = optimization.get(1, 10, batchId);
        optimization.review(1, 10, batchId, new cn.iocoder.yudao.module.quant.api.backtest.ResearchReviewRequest("ACCEPTED", "test", (String)((Map<?,?>) batch.get("researchDraft")).get("evidenceSha256")));
        var admission = (Map<String, Object>) optimization.get(1, 10, batchId).get("paperAdmission");
        assertEquals(true, admission.get("eligible"));
        optimization.reviewAdmission(1, 10, batchId, new cn.iocoder.yudao.module.quant.api.backtest.PaperAdmissionReviewRequest("READY", "test", (String) admission.get("evidenceSha256")));
        var sessions = new PaperSessionService(new cn.iocoder.yudao.module.quant.dal.PaperSessionRepository(jdbc), optimization);
        String session = sessions.create(1, 10, new cn.iocoder.yudao.module.quant.api.backtest.PaperSessionRequest(batchId, parameterSetId));
        best = ((List<Map<String, Object>>) experiments.get(1, 10, id).get("rows")).getFirst();
        assertEquals(session, best.get("paperSessionId")); assertEquals("PENDING_APPROVAL", best.get("paperSessionStatus"));
        assertEquals(second, sessions.get(1, 10, session).get("strategy_version_id"));
        var reviewService=new PaperExperimentReviewService(new cn.iocoder.yudao.module.quant.dal.StrategyExperimentRepository(jdbc),repository,jdbc);
        var review=reviewService.get(1,10,id);
        assertEquals(2,((List<?>)review.get("rows")).size());
        assertThrows(IllegalArgumentException.class,()->reviewService.get(2,10,id));
        assertThrows(IllegalArgumentException.class,()->reviewService.get(1,11,id));
        var before=((List<Map<String,Object>>)review.get("rows")).stream().filter(r->second.equals(r.get("strategyVersionId"))).findFirst().orElseThrow();
        assertNull(before.get("realizedProfit"));assertEquals("NO_READABLE_TELEMETRY",before.get("sampleState"));
        var paperRepo=new cn.iocoder.yudao.module.quant.dal.PaperSessionRepository(jdbc);
        String readiness=UUID.randomUUID().toString(),execution=UUID.randomUUID().toString();
        paperRepo.readiness(readiness,session,"{}","test",true);
        new cn.iocoder.yudao.module.quant.dal.PaperExecutionRepository(jdbc).create(execution,1,10,session,readiness,"test","test-container");
        var observations=new cn.iocoder.yudao.module.quant.dal.PaperObservationRepository(jdbc);
        var runtime=Map.<String,Object>of("heartbeatAgeSeconds",1L,"lastHeartbeatAt",100L,"lastMarketDataAt",100L,"networkErrorCount",0,"fatalErrorCount",0,"soakSeconds",100L,"soakPassed",true);
        var portfolio=new HashMap<String,Object>();portfolio.put("available",true);portfolio.put("estimatedAvailableBalance",998);portfolio.put("openPositions",0);portfolio.put("closedTrades",2);portfolio.put("openOrders",0);portfolio.put("totalOrders",4);portfolio.put("realizedProfit",-2);portfolio.put("investedStake",0);
        for(int i=0;i<105;i++)observations.snapshot(execution,1,10,"STOPPED",runtime,portfolio,"test",1000+i);
        observations.raise(execution,1,10,"NETWORK_ERROR","WARN","test");
        jdbc.update("UPDATE quant_paper_alert SET status='ACKNOWLEDGED' WHERE execution_id=?",execution);
        var observed=((List<Map<String,Object>>)reviewService.get(1,10,id).get("rows")).stream().filter(r->second.equals(r.get("strategyVersionId"))).findFirst().orElseThrow();
        assertEquals(105,((Number)observed.get("snapshotCount")).intValue());assertEquals(1000L,((Number)observed.get("firstObservedAt")).longValue());
        assertEquals(1,observed.get("unresolvedAlerts"));assertEquals(-2.0,((Number)observed.get("realizedProfit")).doubleValue());
        assertEquals(-0.002,((Number)observed.get("realizedReturnRatio")).doubleValue(),0.000001);assertEquals("OBSERVED_TRADES",observed.get("sampleState"));
        // Latest unreadable telemetry must not silently reuse the prior profit or publish fake zero.
        portfolio.put("available",false);portfolio.put("realizedProfit",0);
        observations.snapshot(execution,1,10,"STOPPED",runtime,portfolio,"test",2000);
        var unavailable=((List<Map<String,Object>>)reviewService.get(1,10,id).get("rows")).stream().filter(r->second.equals(r.get("strategyVersionId"))).findFirst().orElseThrow();
        assertNull(unavailable.get("realizedProfit"));assertNull(unavailable.get("realizedReturnRatio"));assertEquals(106,((Number)unavailable.get("snapshotCount")).intValue());

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
    @Test void configuredVersionsAreImmutableReusedAndFlowThroughReports() throws Exception {
        var config = new cn.iocoder.yudao.module.quant.api.backtest.EmaStrategyRequest(12, 48, new BigDecimal("0.015"), new BigDecimal("0.03"));
        String configured = service.createStrategyVersion(1, 10, config);
        assertNotEquals(versionId, configured);
        assertEquals(configured, service.createStrategyVersion(1, 10, config));
        assertEquals(configured, service.createStrategyVersion(1, 10, new cn.iocoder.yudao.module.quant.api.backtest.EmaStrategyRequest(12, 48, new BigDecimal("0.015000"), new BigDecimal("0.030000"))));
        assertEquals(versionId, service.createStrategyVersion(1, 10, EmaStrategyTemplate.defaults()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM quant_strategy", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM quant_strategy_version", Integer.class));
        assertEquals(EmaStrategyTemplate.baseline(), repository.findVersion(1, 10, versionId).get("sourceCode"));
        assertNull(repository.findVersion(2, 10, configured));
        assertNull(repository.findVersion(1, 11, configured));
        var versions = service.listStrategyVersions(1, 10);
        assertTrue(versions.stream().noneMatch(v -> v.containsKey("sourceCode")));
        assertEquals(12, ((Map<?, ?>) versions.stream().filter(v -> configured.equals(v.get("id"))).findFirst().orElseThrow().get("configuration")).get("fastPeriod"));
        var base = request("configured-backtest");
        String id = service.create(1, 10, new BacktestRequest(base.requestKey(), configured, base.parameterSetId(), base.datasetId(), base.startDate(), base.endDate(), base.startingBalance(), base.stakeAmount(), base.fee()));
        assertTrue(repository.claim(id));
        repository.complete(id, new BacktestEngine.Output("test", "{\"totalTrades\":4,\"netProfit\":1.5,\"returnRatio\":0.0015,\"maxDrawdownRatio\":0.002}", "configured-artifact"));
        assertEquals(12, ((Map<?, ?>) service.get(1, 10, id).get("strategyConfiguration")).get("fastPeriod"));
        var report = JsonUtils.getObjectMapper().readTree(new String(service.exportReport(1, 10, id, "json").content(), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(48, report.path("strategy").path("configuration").path("slowPeriod").asInt());
        assertFalse(report.toString().contains("sourceCode"));
        var admissions = new LiveAdmissionService(new cn.iocoder.yudao.module.quant.dal.LiveAdmissionRepository(jdbc));
        String admissionId = admissions.create(1, 10);
        assertThrows(IllegalArgumentException.class, () -> admissions.requireFixedLiveStrategy(1, 10, admissions.get(1, 10, admissionId)));
    }
    @Test void concurrentConfiguredVersionsReuseOneSnapshot() throws Exception {
        var config = new cn.iocoder.yudao.module.quant.api.backtest.EmaStrategyRequest(60, 100, new BigDecimal("0.02"), new BigDecimal("0.04"));
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> service.createStrategyVersion(1, 10, config)));
            var ids = new HashSet<String>();
            for (var future : futures) ids.add(future.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, ids.size());
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM quant_strategy_version", Integer.class));
        }
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
        versionId = service.createStrategyVersion(1, 10, new cn.iocoder.yudao.module.quant.api.backtest.EmaStrategyRequest(12, 48, new BigDecimal("0.015"), new BigDecimal("0.03")));
        String second=service.createParameterSet(1,10,new cn.iocoder.yudao.module.quant.api.backtest.ParameterSetRequest(new BigDecimal("1000"),new BigDecimal("120"),new BigDecimal("0.001")));
        var optimization=new OptimizationService(new cn.iocoder.yudao.module.quant.dal.OptimizationRepository(jdbc),repository,service);
        String id=optimization.create(1,10,new cn.iocoder.yudao.module.quant.api.backtest.OptimizationRequest(versionId,"test","2025-01-11","2025-01-18","2025-01-25",List.of(parameterSetId,second)));
        var members=(List<?>)optimization.get(1,10,id).get("members");
        assertEquals(4,members.size());assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM quant_backtest_task",Integer.class));
        for(Object value:members){var member=(Map<String,Object>)value;String task=(String)member.get("taskId");assertTrue(repository.claim(task));boolean preferred=second.equals(member.get("parameterSetId"));double result="TRAIN".equals(member.get("phase"))?(preferred?0.08:0.04):(preferred?0.03:0.035);repository.complete(task,new BacktestEngine.Output("test","{\"totalTrades\":14,\"netProfit\":1,\"returnRatio\":"+result+",\"maxDrawdownRatio\":0.01}","hash-"+task));}
        var summary=optimization.get(1,10,id);assertEquals(true,summary.get("terminal"));var ranking=(List<Map<String,Object>>)summary.get("ranking");assertEquals(parameterSetId,ranking.getFirst().get("parameterSetId"));assertEquals(false,summary.get("autoSelected"));var draft=(Map<String,Object>)summary.get("researchDraft");assertEquals("quant-research-rules/v1",draft.get("ruleVersion"));assertEquals(false,draft.get("autoApplied"));assertFalse(((List<?>)draft.get("risks")).isEmpty());
        optimization.review(1,10,id,new cn.iocoder.yudao.module.quant.api.backtest.ResearchReviewRequest("ACCEPTED","仅接受研究记录",(String)draft.get("evidenceSha256")));var reviewed=optimization.get(1,10,id);assertEquals(1,((List<?>)reviewed.get("reviews")).size());assertTrue(new String(optimization.exportDraft(1,10,id).content()).contains("不会自动修改参数"));
        var admission=(Map<String,Object>)reviewed.get("paperAdmission");assertEquals(true,admission.get("eligible"));assertEquals(false,admission.get("activationAllowed"));optimization.reviewAdmission(1,10,id,new cn.iocoder.yudao.module.quant.api.backtest.PaperAdmissionReviewRequest("READY","仅确认可准备，不启动执行",(String)admission.get("evidenceSha256")));assertEquals(1,((List<?>)((Map<?,?>)optimization.get(1,10,id).get("paperAdmission")).get("reviews")).size());assertThrows(IllegalArgumentException.class,()->optimization.reviewAdmission(1,10,id,new cn.iocoder.yudao.module.quant.api.backtest.PaperAdmissionReviewRequest("READY","过期证据","0".repeat(64))));
        var sessionRepo=new cn.iocoder.yudao.module.quant.dal.PaperSessionRepository(jdbc);var sessions=new PaperSessionService(sessionRepo,optimization);String session=sessions.create(1,10,new cn.iocoder.yudao.module.quant.api.backtest.PaperSessionRequest(id,parameterSetId));assertEquals(session,sessions.create(1,10,new cn.iocoder.yudao.module.quant.api.backtest.PaperSessionRequest(id,parameterSetId)));var sessionView=sessions.get(1,10,session);assertEquals("PENDING_APPROVAL",sessionView.get("status"));assertEquals(false,sessionView.get("activationAllowed"));sessions.review(1,10,session,new cn.iocoder.yudao.module.quant.api.backtest.PaperSessionReviewRequest("APPROVED","批准后续准备，不启动引擎",(String)sessionView.get("admission_evidence_hash")));assertEquals("APPROVED",sessions.get(1,10,session).get("status"));assertEquals(1,((List<?>)sessions.get(1,10,session).get("reviews")).size());properties.setDockerExecutable(Path.of(System.getProperty("java.home"),"bin","java").toString());properties.setExchangeProxy("http://host.docker.internal:3066");var readiness=new PaperReadinessService(sessionRepo,sessions,repository,properties);readiness.create(1,10,session);String manifest=(String)readiness.list(1,10,session).getFirst().get("manifestJson");assertTrue(manifest.contains("\"dryRun\":true"));assertTrue(manifest.contains("\"executionEnabled\":false"));assertFalse(manifest.toLowerCase().contains("api_key"));var executionRepo=new cn.iocoder.yudao.module.quant.dal.PaperExecutionRepository(jdbc);var executions=new PaperExecutionService(executionRepo,sessionRepo,sessions,properties);String execution=executions.create(1,10,session);assertEquals("WAITING_ENABLE",executions.get(1,10,execution).get("status"));assertEquals(false,executions.get(1,10,execution).get("containerStarted"));var previews=new PaperDryRunPreviewService(executionRepo,sessionRepo,sessions,repository,properties);String preview=previews.create(1,10,execution);assertEquals(preview,previews.create(1,10,execution));var previewView=previews.get(1,10,execution);String previewJson=(String)previewView.get("previewJson");assertTrue(previewJson.contains("\"executed\":false"));assertTrue(previewJson.contains("--read-only"));assertTrue(previewJson.contains("--cap-drop=ALL"));assertFalse(previewJson.contains("--workdir"));assertTrue(Files.readString(Path.of((String)previewView.get("workDirectory")).resolve("config.json")).contains("httpsProxy"));assertTrue(Files.readString(Path.of((String)previewView.get("workDirectory")).resolve("config.json")).contains("\"enable_ws\":false"));assertTrue(Files.readString(Path.of((String)previewView.get("workDirectory")).resolve("config.json")).contains("exit_pricing"));assertTrue(Files.readString(Path.of((String)previewView.get("workDirectory")).resolve("config.json")).contains("/freqtrade/user_data/tradesv3.dryrun.sqlite"));assertFalse(previewJson.toLowerCase().contains("api_key"));Path previewDir=Path.of((String)previewView.get("workDirectory"));assertTrue(Files.exists(previewDir.resolve("config.json")));assertTrue(Files.exists(previewDir.resolve("strategies/QuantEmaBaseline.py")));assertEquals(repository.findVersion(1,10,versionId).get("sourceCode"),Files.readString(previewDir.resolve("strategies/QuantEmaBaseline.py")));assertTrue(Files.readString(previewDir.resolve("strategies/QuantEmaBaseline.py")).contains("timeperiod=48"));Files.writeString(previewDir.resolve("runtime.log"),"2026-09-27 22:00:00,000 - INFO - Wallets synced.\n2026-09-27 22:00:01,000 - INFO - Changing state to: RUNNING\n2026-09-27 22:01:06,000 - INFO - Bot heartbeat\nlast line\n");writePaperTelemetry(previewDir);var observations=new PaperExecutionObservationService(executionRepo,properties);var observation=observations.observe(1,10,execution,20);assertEquals(true,observation.get("preflightPassed"));assertTrue(((String)observation.get("logTail")).contains("last line"));assertEquals(true,((Map<?,?>)observation.get("runtime")).get("soakPassed"));assertEquals(1,((Map<?,?>)observation.get("portfolio")).get("closedTrades"));assertEquals(930.5,(Double)((Map<?,?>)observation.get("portfolio")).get("estimatedAvailableBalance"),0.001);assertThrows(IllegalArgumentException.class,()->observations.observe(1,11,execution,20));var observationRepo=new cn.iocoder.yudao.module.quant.dal.PaperObservationRepository(jdbc);class FakeLauncher implements PaperProcessLauncher{boolean alive=true;int starts;int stops;public Handle start(List<String> command,Path work,String container){starts++;alive=true;return new Handle(){public boolean isAlive(){return alive;}public int exitValue(){return 1;}};}public void stop(String container){stops++;alive=false;}}var launcher=new FakeLauncher();var orderRepo=new cn.iocoder.yudao.module.quant.dal.PaperOrderRepository(jdbc);var reconciliations=new PaperOrderReconciliationService(executionRepo,orderRepo);var safetyStop=new PaperSafetyStopService(executionRepo,launcher);var observationMonitor=new PaperObservationMonitorService(executionRepo,observations,observationRepo,properties,reconciliations,safetyStop);jdbc.update("UPDATE quant_paper_execution SET status='RUNNING' WHERE id=?",execution);properties.setPaperMaxTotalExposure(new BigDecimal("50"));observationMonitor.capture(1,10,execution);assertEquals(1,observationMonitor.snapshots(1,10,execution).size());assertTrue(observationMonitor.alerts(1,10,execution).stream().anyMatch(a->"RISK_LIMIT_BREACH".equals(a.get("alertType"))));assertEquals(1,reconciliations.orders(1,10,execution).size());assertEquals(1,reconciliations.reconciliations(1,10,execution).size());reconciliations.reconcile(1,10,execution);assertEquals(1,reconciliations.orders(1,10,execution).size());try(Connection sqlite=DriverManager.getConnection("jdbc:sqlite:"+previewDir.resolve("tradesv3.dryrun.sqlite"));Statement statement=sqlite.createStatement()){statement.execute("DELETE FROM orders");}jdbc.update("UPDATE quant_paper_order SET last_seen_at=0 WHERE execution_id=?",execution);reconciliations.reconcile(1,10,execution);assertEquals("UNKNOWN",reconciliations.orders(1,10,execution).getFirst().get("orderStatus"));assertEquals("STOPPED",executions.get(1,10,execution).get("status"));assertEquals(1,launcher.stops);var riskAlert=observationMonitor.alerts(1,10,execution).stream().filter(a->"RISK_LIMIT_BREACH".equals(a.get("alertType"))).findFirst().orElseThrow();assertEquals("OPEN",riskAlert.get("status"));var alertId=(String)riskAlert.get("id");assertEquals("ACKNOWLEDGED",observationMonitor.act(1,10,alertId,new cn.iocoder.yudao.module.quant.api.backtest.PaperAlertActionRequest("ACKNOWLEDGE","acknowledged for investigation")));assertEquals("RESOLVED",observationMonitor.act(1,10,alertId,new cn.iocoder.yudao.module.quant.api.backtest.PaperAlertActionRequest("RESOLVE","test timestamp explained")));assertEquals(2,observationMonitor.alertActions(1,10,execution).size());assertThrows(IllegalArgumentException.class,()->observationMonitor.act(1,11,alertId,new cn.iocoder.yudao.module.quant.api.backtest.PaperAlertActionRequest("ACKNOWLEDGE","foreign owner")));jdbc.update("UPDATE quant_paper_execution SET status='STOPPED' WHERE id=?",execution);jdbc.update("UPDATE quant_paper_observation_snapshot SET observed_at=0 WHERE execution_id=?",execution);assertEquals(1,observationMonitor.cleanup());assertTrue(observationMonitor.snapshots(1,10,execution).isEmpty());jdbc.update("UPDATE quant_paper_execution SET status='WAITING_ENABLE' WHERE id=?",execution);var tokens=new PaperStartTokenService(executionRepo,properties);var issued=tokens.issue(1,10,execution,new cn.iocoder.yudao.module.quant.api.backtest.PaperStartTokenRequest((String)previewView.get("previewHash"),"CONFIRM_DRY_RUN_START","确认预览内容并申请短时令牌"));String rawToken=(String)issued.get("token");assertEquals(43,rawToken.length());assertEquals("ISSUED",tokens.latest(1,10,execution).get("status"));assertNotEquals(rawToken,jdbc.queryForObject("SELECT token_hash FROM quant_paper_start_token WHERE id=?",String.class,issued.get("id")));properties.setPaperExecutionEnabled(true);var gate=new PaperExecutionGateService(executionRepo,tokens,properties);var runtime=new PaperRuntimeService(executionRepo,gate,launcher,observationMonitor);runtime.start(1,10,execution,new cn.iocoder.yudao.module.quant.api.backtest.PaperExecutionStartRequest((String)previewView.get("previewHash"),rawToken));assertEquals("RUNNING",executions.get(1,10,execution).get("status"));assertEquals("CONSUMED",tokens.latest(1,10,execution).get("status"));assertEquals(1,launcher.starts);runtime.stop(1,10,execution,new cn.iocoder.yudao.module.quant.api.backtest.PaperExecutionStopRequest("人工停止模拟盘"));assertEquals("STOPPED",executions.get(1,10,execution).get("status"));String repeatedExecution=executions.create(1,10,session);assertNotEquals(execution,repeatedExecution);assertEquals(repeatedExecution,executions.create(1,10,session));assertEquals(2,launcher.stops);jdbc.update("UPDATE quant_paper_execution SET status='RUNNING' WHERE id=?",execution);runtime.recoverInterrupted();assertEquals("FAILED",executions.get(1,10,execution).get("status"));assertEquals(3,launcher.stops);assertTrue(((List<?>)executions.get(1,10,execution).get("audits")).stream().map(Object::toString).anyMatch(x->x.contains("RISK_STOPPED")));properties.setPaperExecutionEnabled(false);assertThrows(IllegalArgumentException.class,()->sessions.get(1,11,session));
        assertThrows(IllegalArgumentException.class,()->optimization.get(1,11,id));
    }

    @Test void liveAdmissionReportIsImmutableReadOnlyAndDoubleConfirmed() throws Exception {
        String task=service.create(1,10,request("live-admission"));assertTrue(repository.claim(task));repository.complete(task,new BacktestEngine.Output("freqtrade-test","{\"totalTrades\":4}","artifact-live"));
        String batch=UUID.randomUUID().toString(),session=UUID.randomUUID().toString(),readiness=UUID.randomUUID().toString(),execution=UUID.randomUUID().toString();long now=System.currentTimeMillis();
        jdbc.update("INSERT INTO quant_optimization_batch(id,tenant_id,owner_id,dataset_id,strategy_version_id,train_start,split_date,validation_end,created_at) VALUES(?,?,?,?,?,?,?,?,?)",batch,1,10,"test",versionId,"2025-01-01","2025-01-08","2025-01-15",now);
        jdbc.update("INSERT INTO quant_paper_session(id,tenant_id,owner_id,batch_id,strategy_version_id,parameter_set_id,admission_evidence_hash,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",session,1,10,batch,versionId,parameterSetId,"a".repeat(64),"APPROVED",now,now);
        jdbc.update("INSERT INTO quant_paper_readiness_snapshot(id,session_id,manifest_json,manifest_hash,ready,created_at) VALUES(?,?,?,?,?,?)",readiness,session,"{}","b".repeat(64),true,now);
        jdbc.update("INSERT INTO quant_paper_execution(id,tenant_id,owner_id,session_id,readiness_snapshot_id,readiness_hash,status,container_name,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",execution,1,10,session,readiness,"b".repeat(64),"STOPPED","quant-platform-paper-"+execution,now,now);
        for(int i=0;i<240;i++){long observed=now+i*61000L;jdbc.update("INSERT INTO quant_paper_observation_snapshot(id,execution_id,tenant_id,owner_id,execution_status,heartbeat_age_seconds,last_heartbeat_at,last_market_data_at,network_error_count,fatal_error_count,soak_seconds,soak_passed,database_available,estimated_available_balance,open_positions,closed_trades,open_orders,total_orders,realized_profit,invested_stake,evidence_hash,observed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),execution,1,10,"RUNNING",1,observed,observed,0,0,i*61L,true,true,new BigDecimal("1000"),0,0,0,0,BigDecimal.ZERO,BigDecimal.ZERO,"c".repeat(64),observed);}
        jdbc.update("INSERT INTO quant_paper_order_reconciliation(id,execution_id,tenant_id,owner_id,source_order_count,ledger_order_count,unknown_order_count,reconciliation_status,evidence_hash,error_message,reconciled_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),execution,1,10,1,1,0,"FAILED","d".repeat(64),"expected lock timeout",now);
        jdbc.update("INSERT INTO quant_paper_order_reconciliation(id,execution_id,tenant_id,owner_id,source_order_count,ledger_order_count,unknown_order_count,reconciliation_status,evidence_hash,error_message,reconciled_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),execution,1,10,1,1,0,"PASSED","e".repeat(64),null,now+1);
        jdbc.update("INSERT INTO quant_paper_execution_audit(id,execution_id,actor_id,event_type,from_status,to_status,message,created_at) VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),execution,10,"RISK_STOPPED","RUNNING","STOPPED","risk rehearsal",now+2);
        var admissions=new LiveAdmissionService(new cn.iocoder.yudao.module.quant.dal.LiveAdmissionRepository(jdbc));String report=admissions.create(1,10);assertEquals(report,admissions.create(1,10));var view=admissions.get(1,10,report);String hash=(String)view.get("reportHash"),json=(String)view.get("reportJson");assertTrue(json.contains("EVIDENCE_COMPLETE"));assertTrue(json.contains("\"withdrawalAllowed\":false"));assertTrue(json.contains("\"liveTradingAllowed\":false"));assertEquals(false,view.get("activationAllowed"));assertThrows(IllegalArgumentException.class,()->admissions.confirm(1,10,report,new cn.iocoder.yudao.module.quant.api.backtest.LiveAdmissionConfirmationRequest("EVIDENCE_REVIEW","WRONG","reviewed",hash)));
        assertEquals("PENDING_CONFIRMATION",admissions.confirm(1,10,report,new cn.iocoder.yudao.module.quant.api.backtest.LiveAdmissionConfirmationRequest("EVIDENCE_REVIEW","CONFIRM_EVIDENCE_REVIEWED","证据逐项复核",hash)));assertEquals("DOUBLE_CONFIRMED",admissions.confirm(1,10,report,new cn.iocoder.yudao.module.quant.api.backtest.LiveAdmissionConfirmationRequest("KEY_BOUNDARY_REVIEW","CONFIRM_KEY_BOUNDARY_ACCEPTED","接受密钥最小权限边界",hash)));assertEquals(2,((List<?>)admissions.get(1,10,report).get("confirmations")).size());assertThrows(IllegalArgumentException.class,()->admissions.get(1,11,report));assertThrows(IllegalArgumentException.class,()->admissions.confirm(1,10,report,new cn.iocoder.yudao.module.quant.api.backtest.LiveAdmissionConfirmationRequest("EVIDENCE_REVIEW","CONFIRM_EVIDENCE_REVIEWED","duplicate",hash)));assertTrue(new String(admissions.export(1,10,report).content(),java.nio.charset.StandardCharsets.UTF_8).contains("DOUBLE_CONFIRMED"));
        var controls=new LiveControlService(new cn.iocoder.yudao.module.quant.dal.LiveControlRepository(jdbc),admissions,properties,new DpapiLiveCredentialProvider(properties));String policy=controls.create(1,10,report);assertEquals(policy,controls.create(1,10,report));var policyView=controls.get(1,10,policy);assertEquals("HALTED",policyView.get("status"));assertEquals(false,policyView.get("liveExecutionEnabled"));assertEquals(false,policyView.get("realOrderEndpointAvailable"));assertEquals("ARMED_OFFLINE",controls.arm(1,10,policy,new cn.iocoder.yudao.module.quant.api.backtest.LiveControlArmRequest("CONFIRM_OFFLINE_GATE_ARM","启用离线门禁测试")));
        var allowedRequest=new cn.iocoder.yudao.module.quant.api.backtest.LiveOrderCheckRequest("offline-order-001","BUY","LIMIT",new BigDecimal("100"),new BigDecimal("0.05"),BigDecimal.ZERO,BigDecimal.ZERO,0);var allowed=controls.check(1,10,policy,allowedRequest);assertEquals("ALLOWED_OFFLINE",allowed.get("decision"));assertEquals(false,allowed.get("executed"));assertEquals(allowed.get("id"),controls.check(1,10,policy,allowedRequest).get("id"));assertThrows(IllegalArgumentException.class,()->controls.check(1,10,policy,new cn.iocoder.yudao.module.quant.api.backtest.LiveOrderCheckRequest("offline-order-001","BUY","LIMIT",new BigDecimal("100"),new BigDecimal("0.06"),BigDecimal.ZERO,BigDecimal.ZERO,0)));
        class FakeLiveClient implements LiveTradingClient {int placed,canceled;public boolean configured(){return true;}public String accountBalance(){return "{\"code\":\"0\",\"data\":[{\"details\":[{\"ccy\":\"BTC\",\"eqUsd\":\"0\"}]}]}";}public String pendingOrders(){return "{\"code\":\"0\",\"data\":[]}";}public String placeSpotLimitOrder(String c,String s,String p,String a){placed++;return "{\"code\":\"0\",\"data\":[{\"ordId\":\"okx-1\",\"sCode\":\"0\",\"sMsg\":\"\"}]}";}public String getOrder(String c){return "{\"code\":\"0\",\"data\":[{\"state\":\""+(canceled>0?"canceled":"live")+"\",\"accFillSz\":\"0\",\"avgPx\":\"\"}]}";}public String cancelOrder(String c){canceled++;return "{\"code\":\"0\",\"data\":[{\"sCode\":\"0\",\"sMsg\":\"\"}]}";}}var fakeLive=new FakeLiveClient();properties.setLiveExecutionEnabled(true);var liveOrders=new LiveOrderService(new cn.iocoder.yudao.module.quant.dal.LiveControlRepository(jdbc),new cn.iocoder.yudao.module.quant.dal.LiveOrderRepository(jdbc),fakeLive,properties,controls,transactions);var issued=liveOrders.issue(1,10,policy,new cn.iocoder.yudao.module.quant.api.backtest.LiveOrderTokenRequest("offline-order-001","CONFIRM_LIVE_ORDER","首单桩验证"));String liveToken=(String)issued.get("token");assertNotEquals(liveToken,jdbc.queryForObject("SELECT token_hash FROM quant_live_order_token WHERE decision_id=?",String.class,allowed.get("id")));var exchangeOrder=liveOrders.execute(1,10,policy,new cn.iocoder.yudao.module.quant.api.backtest.LiveOrderExecuteRequest(liveToken));assertEquals("LIVE",exchangeOrder.get("status"));assertEquals(1,fakeLive.placed);assertThrows(IllegalArgumentException.class,()->liveOrders.execute(1,10,policy,new cn.iocoder.yudao.module.quant.api.backtest.LiveOrderExecuteRequest(liveToken)));assertEquals("LIVE",liveOrders.refresh(1,10,(String)exchangeOrder.get("id")).get("status"));assertEquals("CANCELED",liveOrders.cancel(1,10,(String)exchangeOrder.get("id")).get("status"));assertEquals(1,fakeLive.canceled);var autoRepo=new cn.iocoder.yudao.module.quant.dal.LiveAutomationRepository(jdbc);String auto=autoRepo.create(policy,1,10,new BigDecimal("5"),new BigDecimal("5"),new BigDecimal("100"));assertEquals("RUNNING",autoRepo.get(1,10,auto).get("status"));autoRepo.heartbeat(auto,new BigDecimal("99"),123L);autoRepo.snapshot(auto,1,10,new BigDecimal("99"),BigDecimal.ZERO,0,0,BigDecimal.ONE,"PASSED","a".repeat(64),null);assertTrue(autoRepo.insertSignal(UUID.randomUUID().toString(),auto,policy,1,10,123L,"NONE",new BigDecimal("100"),new BigDecimal("99"),new BigDecimal("98"),"b".repeat(64),null));assertFalse(autoRepo.insertSignal(UUID.randomUUID().toString(),auto,policy,1,10,123L,"NONE",new BigDecimal("100"),new BigDecimal("99"),new BigDecimal("98"),"b".repeat(64),null));assertTrue(autoRepo.stop(auto,"STOPPED","test"));assertEquals(1,autoRepo.snapshots(1,10,auto).size());var performance=new LivePerformanceService(jdbc,autoRepo);assertEquals(0,performance.get(1,10,auto).get("orderCount"));assertThrows(IllegalArgumentException.class,()->performance.get(2,10,auto));assertThrows(IllegalArgumentException.class,()->performance.get(1,11,auto));
        // Configured admission cannot borrow another version's paper evidence.
        String configured=service.createStrategyVersion(1,10,new cn.iocoder.yudao.module.quant.api.backtest.EmaStrategyRequest(12,48,new BigDecimal("0.015"),new BigDecimal("0.03")));
        var base=request("configured-live-binding");
        String configuredTask=service.create(1,10,new BacktestRequest(base.requestKey(),configured,base.parameterSetId(),base.datasetId(),base.startDate(),base.endDate(),base.startingBalance(),base.stakeAmount(),base.fee()));
        assertTrue(repository.claim(configuredTask));repository.complete(configuredTask,new BacktestEngine.Output("test","{\"totalTrades\":20}","configured-live-artifact"));
        String missingPaper=admissions.create(1,10,configuredTask);
        assertThrows(IllegalArgumentException.class,()->admissions.liveStrategy(1,10,admissions.get(1,10,missingPaper)));
        assertThrows(IllegalArgumentException.class,()->admissions.create(2,10,configuredTask));
        assertThrows(IllegalArgumentException.class,()->admissions.create(1,11,configuredTask));
        jdbc.update("UPDATE quant_paper_session SET strategy_version_id=? WHERE id=?",configured,session);
        String configuredReport=admissions.create(1,10,configuredTask);
        assertNotEquals(missingPaper,configuredReport);
        var configuredView=admissions.get(1,10,configuredReport);
        assertEquals(configured,admissions.liveStrategy(1,10,configuredView).get("strategyVersionId"));
        assertThrows(IllegalArgumentException.class,()->controls.create(1,10,configuredReport));
        String configuredHash=String.valueOf(configuredView.get("reportHash"));
        admissions.confirm(1,10,configuredReport,new cn.iocoder.yudao.module.quant.api.backtest.LiveAdmissionConfirmationRequest("EVIDENCE_REVIEW","CONFIRM_EVIDENCE_REVIEWED","test binding",configuredHash));
        admissions.confirm(1,10,configuredReport,new cn.iocoder.yudao.module.quant.api.backtest.LiveAdmissionConfirmationRequest("KEY_BOUNDARY_REVIEW","CONFIRM_KEY_BOUNDARY_ACCEPTED","test binding",configuredHash));
        String configuredPolicy=controls.create(1,10,configuredReport);
        assertEquals("HALTED",controls.get(1,10,configuredPolicy).get("status"));
        assertEquals(12,((Map<?,?>)controls.strategy(1,10,configuredPolicy).get("configuration")).get("fastPeriod"));
        jdbc.update("UPDATE quant_strategy_version SET source_code=CONCAT(source_code,'# changed') WHERE id=?",configured);
        assertThrows(IllegalArgumentException.class,()->controls.arm(1,10,configuredPolicy,new cn.iocoder.yudao.module.quant.api.backtest.LiveControlArmRequest("CONFIRM_OFFLINE_GATE_ARM","must reject changed source")));

        jdbc.update("UPDATE quant_live_strategy_signal SET client_order_id=? WHERE session_id=?", "offline-order-001",auto);
        var costRepo=new cn.iocoder.yudao.module.quant.dal.LiveOrderRepository(jdbc);
        costRepo.updateCosts((String)exchangeOrder.get("id"),"FILLED",new BigDecimal("0.01"),new BigDecimal("100"),"0","",new BigDecimal("-0.0001"),"BTC",BigDecimal.ZERO,"");
        var attributed=performance.get(1,10,auto);assertEquals(1,attributed.get("orderCount"));assertEquals(0,new BigDecimal("-0.01").compareTo((BigDecimal)attributed.get("netContribution")));assertEquals(0,new BigDecimal("0.0099").compareTo((BigDecimal)attributed.get("netPositionBtc")));
        // Aggregate replacement is idempotent, never sums the same fee twice.
        costRepo.updateCosts((String)exchangeOrder.get("id"),"FILLED",new BigDecimal("0.01"),new BigDecimal("100"),"0","",new BigDecimal("-0.0001"),"BTC",BigDecimal.ZERO,"");
        assertEquals(attributed.get("netContribution"),performance.get(1,10,auto).get("netContribution"));properties.setLiveExecutionEnabled(false);
        var rejected=controls.check(1,10,policy,new cn.iocoder.yudao.module.quant.api.backtest.LiveOrderCheckRequest("offline-order-002","BUY","LIMIT",new BigDecimal("100"),new BigDecimal("0.11"),BigDecimal.ZERO,BigDecimal.ZERO,0));assertEquals("REJECTED",rejected.get("decision"));assertEquals("MAX_ORDER_NOTIONAL",rejected.get("reasonCode"));assertEquals("HALTED",controls.emergencyStop(1,10,policy,new cn.iocoder.yudao.module.quant.api.backtest.LiveControlStopRequest("验证紧急停机")));var halted=controls.check(1,10,policy,new cn.iocoder.yudao.module.quant.api.backtest.LiveOrderCheckRequest("offline-order-003","BUY","LIMIT",new BigDecimal("100"),new BigDecimal("0.01"),BigDecimal.ZERO,BigDecimal.ZERO,0));assertEquals("GATE_HALTED",halted.get("reasonCode"));assertTrue(((List<?>)controls.get(1,10,policy).get("audits")).size()>=5);assertThrows(IllegalArgumentException.class,()->controls.get(1,11,policy));
    }

    @Test void okxPrivateAdapterSignsRequestsAndRemainsDisabledWithoutCredentials(){
        assertEquals("pJzwUbSE3haU1Oef34IJ1gGR9JEqf2pPTJhcq/byw5E=",OkxPrivateApiClient.sign("2026-09-29T00:00:00.000Z","GET","/api/v5/account/balance?ccy=BTC,USDT","","test-secret"));
        var provider=new DpapiLiveCredentialProvider(properties);var client=new OkxPrivateApiClient(properties,provider);assertFalse(client.configured());assertThrows(IllegalStateException.class,client::accountBalance);assertThrows(IllegalStateException.class,()->client.placeSpotLimitOrder("offline-order","buy","100","0.01"));
    }

    @Test void fixedLiveStrategyUsesOnlyClosedCandlesAndProducesStableSignal(){
        List<List<String>> rows=new ArrayList<>();long start=1700000000000L;
        for(int i=0;i<60;i++){String close=String.valueOf(100-i);rows.add(List.of(String.valueOf(start+i*3600000L),close,close,close,close,"1","0","0","1"));}
        rows.add(List.of(String.valueOf(start+60*3600000L),"400","400","400","400","1","0","0","1"));
        rows.add(List.of(String.valueOf(start+61*3600000L),"1","1","1","1","1","0","0","0"));
        Collections.reverse(rows);String json=JsonUtils.toJsonString(Map.of("code","0","data",rows));
        var signal=LiveAutomationService.evaluate(json);assertEquals("BUY",signal.type());assertEquals(start+60*3600000L,signal.candleAt());assertEquals(new BigDecimal("400"),signal.close());
        assertEquals(signal,LiveAutomationService.evaluate(json));
        assertEquals(new BigDecimal("0E-8"),LiveAutomationService.sellAmount(BigDecimal.ZERO,new BigDecimal("1"),new BigDecimal("5"),new BigDecimal("100000")));
        assertEquals(new BigDecimal("0.00003930"),LiveAutomationService.sellAmount(new BigDecimal("0.00003937"),new BigDecimal("0.00003930"),new BigDecimal("5"),new BigDecimal("100000")));
        assertEquals(new BigDecimal("0.00005000"),LiveAutomationService.sellAmount(new BigDecimal("1"),new BigDecimal("1"),new BigDecimal("5"),new BigDecimal("100000")));
        String ticker="{\"code\":\"0\",\"data\":[{\"askPx\":\"84237.2\",\"bidPx\":\"84236.9\",\"last\":\"84237.0\"}]}";
        assertEquals(new BigDecimal("84237.2"),LiveAutomationService.executionPrice(ticker,"BUY"));
        assertEquals(new BigDecimal("84236.9"),LiveAutomationService.executionPrice(ticker,"SELL"));
    }

    void writePaperTelemetry(Path directory) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("tradesv3.dryrun.sqlite"))) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE trades(id INTEGER PRIMARY KEY,is_open BOOLEAN,realized_profit FLOAT,stake_amount FLOAT,open_date DATETIME,close_date DATETIME)");
                statement.execute("CREATE TABLE orders(id INTEGER PRIMARY KEY,ft_trade_id INTEGER,ft_order_side VARCHAR,ft_pair VARCHAR,order_id VARCHAR,status VARCHAR,order_type VARCHAR,price FLOAT,amount FLOAT,filled FLOAT,cost FLOAT,ft_is_open BOOLEAN,order_date DATETIME,order_filled_date DATETIME,order_update_date DATETIME)");
                statement.execute("CREATE TABLE wallet_history(id INTEGER PRIMARY KEY,timestamp DATETIME,balance FLOAT,total_quote FLOAT,total_position_value FLOAT)");
                statement.execute("INSERT INTO trades VALUES(1,0,5.5,50,'2026-09-27 22:00:00','2026-09-27 22:30:00')");statement.execute("INSERT INTO trades VALUES(2,1,0,75,'2026-09-27 22:40:00',NULL)");
                statement.execute("INSERT INTO orders VALUES(1,1,'buy','BTC/USDT','dry-order-1','closed','limit',100,1,1,100,0,'2026-09-27 22:00:00','2026-09-27 22:30:00','2026-09-27 22:30:00')");
                statement.execute("INSERT INTO wallet_history VALUES(1,'2026-09-27 22:30:00',1005.5,1005.5,0)");
            }
        }
    }
}
