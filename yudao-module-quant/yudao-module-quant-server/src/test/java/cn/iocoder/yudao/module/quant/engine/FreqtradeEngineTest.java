package cn.iocoder.yudao.module.quant.engine;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.BacktestRequest;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class FreqtradeEngineTest {
    @TempDir Path root;
    @Test void commandHasNoTradingOrUserSuppliedExecutable() {
        var props = new QuantProperties();
        var engine = new FreqtradeBacktestEngine(props);
        var req = new BacktestRequest("key", UUID.randomUUID().toString(), UUID.randomUUID().toString(), "test", "2025-01-11", "2025-01-13", new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("0.001"));
        var data = new DatasetRegistry.Dataset("test", "okx", "hash", "test", root, 0, 0, 300);
        var input = new BacktestEngine.Input(UUID.randomUUID().toString(), req, data, "source", "hash");
        var args = engine.command(input, root);
        assertTrue(args.contains("backtesting")); assertFalse(args.contains("trade")); assertFalse(args.contains("-e"));
        assertEquals(true, engine.configuration(input).get("dry_run"));
        assertEquals("spot", engine.configuration(input).get("trading_mode"));
        assertThrows(IllegalArgumentException.class, () -> FreqtradeBacktestEngine.container("other-project"));
    }
    @Test void parsesZeroTradesButRejectsTruncatedCoverage() throws Exception {
        var summary = new LinkedHashMap<String,Object>();
        summary.put("total_trades",0); summary.put("profit_total",0); summary.put("profit_total_abs",0);
        summary.put("max_drawdown_account",0); summary.put("trades",List.of());
        summary.put("backtest_start_ts",LocalDate.of(2025,1,11).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli());
        summary.put("backtest_end_ts",LocalDate.of(2025,1,13).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()-3600000);
        Path zip=root.resolve("result.zip"); writeZip(zip,summary);
        assertEquals("2026.8",FreqtradeBacktestEngine.readResult(zip,"2025-01-11","2025-01-13","2026.8").engineVersion());
        var normalized=JsonUtils.getObjectMapper().readTree(FreqtradeBacktestEngine.readResult(zip,"2025-01-11","2025-01-13","2026.8").resultJson());
        assertEquals(1,normalized.path("schemaVersion").asInt());
        assertEquals(0,normalized.path("totalTrades").asInt());
        assertFalse(normalized.has("profit_total_abs"));
        summary.put("backtest_end_ts",0); writeZip(zip,summary);
        assertThrows(IllegalStateException.class,()->FreqtradeBacktestEngine.readResult(zip,"2025-01-11","2025-01-13","2026.8"));
    }
    @Test void versionMustHaveActualStartupEvidence() throws Exception {
        Path log=root.resolve("engine.log");
        Files.writeString(log,"2026-09-26 16:44:11,682 - freqtrade - INFO - freqtrade 2026.8\n");
        assertEquals("2026.8",FreqtradeBacktestEngine.readVersion(log));
        Files.writeString(log,"unrelated log");
        assertThrows(IllegalStateException.class,()->FreqtradeBacktestEngine.readVersion(log));
    }
    @Test void historicalMarketOverridesStrategyTimeframeAndRejectsWrongExport() throws Exception {
        var props=new QuantProperties();var engine=new FreqtradeBacktestEngine(props);
        var req=new BacktestRequest("key",UUID.randomUUID().toString(),UUID.randomUUID().toString(),"test","2025-02-10","2025-02-12",new BigDecimal("1000"),new BigDecimal("100"),new BigDecimal("0.001"));
        var dataset=new DatasetRegistry.Dataset("test","binance","hash","test",root,0,0,300,"ETH/USDT","4h");
        var input=new BacktestEngine.Input(UUID.randomUUID().toString(),req,dataset,"source","hash");
        var args=engine.command(input,root);assertEquals("4h",args.get(args.indexOf("--timeframe")+1));
        assertEquals(List.of("ETH/USDT"),((Map<?,?>)engine.configuration(input).get("exchange")).get("pair_whitelist"));
        assertEquals(true,engine.configuration(input).get("dry_run"));
        var summary=new LinkedHashMap<String,Object>(Map.of("total_trades",0,"profit_total",0,"profit_total_abs",0,"max_drawdown_account",0,"trades",List.of(),"timeframe","4h",
                "backtest_start_ts",Instant.parse("2025-02-10T00:00:00Z").toEpochMilli(),"backtest_end_ts",Instant.parse("2025-02-11T20:00:00Z").toEpochMilli()));
        Path zip=root.resolve("eth-four.zip");writeZip(zip,summary);var market=new HistoricalMarket("ETH/USDT","4h");
        var result=JsonUtils.getObjectMapper().readTree(FreqtradeBacktestEngine.readResult(zip,"2025-02-10","2025-02-12","2026.8",market).resultJson());
        assertEquals("ETH/USDT",result.path("pair").asText());assertEquals("4h",result.path("timeframe").asText());
        summary.put("timeframe","1h");writeZip(zip,summary);assertThrows(IllegalStateException.class,()->FreqtradeBacktestEngine.readResult(zip,"2025-02-10","2025-02-12","2026.8",market));
        summary.put("timeframe","4h");summary.put("total_trades",1);summary.put("trades",List.of(Map.of("pair","BTC/USDT","profit_abs",1)));writeZip(zip,summary);
        assertThrows(IllegalStateException.class,()->FreqtradeBacktestEngine.readResult(zip,"2025-02-10","2025-02-12","2026.8",market));
    }
    void writeZip(Path path,Map<String,Object> summary) throws Exception {
        try(var zip=new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("result.json"));
            zip.write(JsonUtils.toJsonByte(Map.of("strategy",Map.of("QuantEmaBaseline",summary))));
            zip.closeEntry();
        }
    }
}
