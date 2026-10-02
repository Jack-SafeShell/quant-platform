package cn.iocoder.yudao.module.quant;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.*;
import cn.iocoder.yudao.module.quant.engine.*;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import cn.iocoder.yudao.module.quant.service.EmaStrategyTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in actual Freqtrade backtest. Reads the existing dataset; writes only to a separate workspace. */
@EnabledIfEnvironmentVariable(named = "QUANT_STRATEGY_SMOKE", matches = "true")
class ConfiguredEmaSmokeTest {
    @Test void configurableStrategyRunsOnActualHistoricalDataset() throws Exception {
        Path dataRoot = Path.of(System.getenv("QUANT_STRATEGY_SMOKE_DATA_ROOT")).toAbsolutePath().normalize();
        Path work = Path.of(System.getenv("QUANT_STRATEGY_SMOKE_WORKSPACE")).toAbsolutePath().normalize();
        assertNotEquals(dataRoot, work, "Smoke must not use the active service workspace");
        var dataProps = new QuantProperties(); dataProps.setWorkspace(dataRoot.toString());
        var dataset = new DatasetRegistry(dataProps).load("okx-btc-202608", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1));
        var props = new QuantProperties(); props.setWorkspace(work.toString());
        props.setExchangeProxy(Optional.ofNullable(System.getenv("QUANT_EXCHANGE_PROXY")).orElse(""));
        var config = new EmaStrategyRequest(12, 48, new BigDecimal("0.015"), new BigDecimal("0.03"));
        String source = EmaStrategyTemplate.render(config), id = UUID.randomUUID().toString();
        var request = new BacktestRequest("configured-smoke", UUID.randomUUID().toString(), UUID.randomUUID().toString(), dataset.id(), "2026-08-01", "2026-09-01", new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("0.001"));
        var engine = new FreqtradeBacktestEngine(props);
        var result = engine.run(new BacktestEngine.Input(id, request, dataset, source, DatasetRegistry.hash(source.getBytes(StandardCharsets.UTF_8))));
        var metrics = JsonUtils.getObjectMapper().readTree(result.resultJson());
        assertEquals(1, metrics.path("schemaVersion").asInt());
        assertTrue(metrics.path("totalTrades").asInt() > 0, "This fixture must exercise actual EMA trades");
        var summary = new TreeMap<String, Object>();
        summary.put("jobId", id); summary.put("strategyConfiguration", EmaStrategyTemplate.configuration(config));
        summary.put("strategyHash", DatasetRegistry.hash(source.getBytes(StandardCharsets.UTF_8)));
        summary.put("datasetHash", dataset.sha256()); summary.put("engineVersion", result.engineVersion());
        summary.put("artifactHash", result.artifactHash()); summary.put("metrics", metrics);
        Files.writeString(work.resolve("configured-strategy-smoke.json"), JsonUtils.toJsonPrettyString(summary), StandardCharsets.UTF_8);
    }
}
