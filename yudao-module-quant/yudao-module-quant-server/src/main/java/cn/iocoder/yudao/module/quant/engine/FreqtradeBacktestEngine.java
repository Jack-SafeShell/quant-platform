package cn.iocoder.yudao.module.quant.engine;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

@Component
public class FreqtradeBacktestEngine implements BacktestEngine {
    public static final String STRATEGY = "QuantEmaBaseline";
    private final QuantProperties properties;
    public FreqtradeBacktestEngine(QuantProperties properties) { this.properties = properties; }
    @Override
    public Output run(Input input) throws Exception {
        if (!DatasetRegistry.hash(input.strategySource().getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(input.strategyHash()))
            throw new IllegalStateException("策略快照摘要不符");
        Path job = Path.of(properties.getWorkspace()).toAbsolutePath().resolve("jobs").resolve(UUID.fromString(input.taskId()).toString());
        Files.createDirectories(job.resolve("strategies"));
        Files.createDirectories(job.resolve("results"));
        Files.createDirectories(job.resolve("data"));
        Files.writeString(job.resolve("strategies/" + STRATEGY + ".py"), input.strategySource());
        Files.copy(input.dataset().directory().resolve("BTC_USDT-1h.json"), job.resolve("data/BTC_USDT-1h.json"));
        if (!DatasetRegistry.hash(Files.readAllBytes(job.resolve("data/BTC_USDT-1h.json"))).equals(input.dataset().sha256()))
            throw new IllegalStateException("行情在任务创建后发生变更");
        Files.writeString(job.resolve("config.json"), JsonUtils.toJsonString(configuration(input)));
        Process process = new ProcessBuilder(command(input, job)).redirectErrorStream(true)
                .redirectOutput(job.resolve("engine.log").toFile()).start();
        try {
            if (!process.waitFor(properties.getTimeoutSeconds(), TimeUnit.SECONDS)) {
                stop(input.taskId());
                throw new IllegalStateException("回测超时，已请求终止专属容器");
            }
            if (process.exitValue() != 0) throw new IllegalStateException("Freqtrade 回测失败，详见本机任务 engine.log");
        } catch (InterruptedException e) {
            stop(input.taskId());
            Thread.currentThread().interrupt();
            throw e;
        } finally { if (process.isAlive()) process.destroyForcibly(); }
        List<Path> archives;
        try (var stream = Files.list(job.resolve("results"))) { archives = stream.filter(p -> p.toString().endsWith(".zip")).toList(); }
        if (archives.size() != 1) throw new IllegalStateException("引擎未生成唯一结果归档");
        return readResult(archives.getFirst(), input.parameters().startDate(), input.parameters().endDate(), readVersion(job.resolve("engine.log")));
    }
    Map<String, Object> configuration(Input input) {
        Map<String, Object> exchange = new LinkedHashMap<>();
        exchange.put("name", input.dataset().exchange());
        exchange.put("key", ""); exchange.put("secret", ""); exchange.put("password", "");
        exchange.put("pair_whitelist", List.of("BTC/USDT")); exchange.put("pair_blacklist", List.of());
        exchange.put("enable_ws", false);
        if (!properties.getExchangeProxy().isBlank()) {
            exchange.put("ccxt_config", Map.of("httpsProxy", properties.getExchangeProxy()));
            exchange.put("ccxt_async_config", Map.of("httpsProxy", properties.getExchangeProxy()));
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("dry_run", true); config.put("trading_mode", "spot"); config.put("max_open_trades", 1);
        config.put("stake_currency", "USDT"); config.put("stake_amount", input.parameters().stakeAmount());
        config.put("dry_run_wallet", input.parameters().startingBalance()); config.put("tradable_balance_ratio", 1.0);
        config.put("timeframe", "1h"); config.put("exchange", exchange); config.put("dataformat_ohlcv", "json");
        config.put("pairlists", List.of(Map.of("method", "StaticPairList")));
        config.put("entry_pricing", Map.of("price_side", "other", "use_order_book", false));
        config.put("exit_pricing", Map.of("price_side", "other", "use_order_book", false));
        config.put("api_server", Map.of("enabled", false, "listen_ip_address", "127.0.0.1", "listen_port", 8080,
                "username", "disabled", "password", "", "jwt_secret_key", UUID.randomUUID().toString()));
        config.put("telegram", Map.of("enabled", false, "token", "", "chat_id", ""));
        return config;
    }
    List<String> command(Input input, Path job) {
        return List.of(properties.getDockerExecutable(), "run", "--rm", "--name", container(input.taskId()),
                "--label", "com.quant-platform.purpose=backtest", "--memory", "2g", "--cpus", "2",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--read-only", "--tmpfs", "/tmp",
                "--mount", "type=bind,source=" + job + ",target=/freqtrade/user_data", properties.getImage(),
                "backtesting", "--config", "/freqtrade/user_data/config.json", "--strategy", STRATEGY,
                "--strategy-path", "/freqtrade/user_data/strategies", "--datadir", "/freqtrade/user_data/data",
                "--data-format-ohlcv", "json", "--timerange", input.parameters().startDate().replace("-", "") + "-" + input.parameters().endDate().replace("-", ""),
                "--fee", input.parameters().fee().toPlainString(), "--cache", "none", "--export", "trades",
                "--backtest-directory", "/freqtrade/user_data/results");
    }
    static String container(String taskId) { return "quant-platform-bt-" + UUID.fromString(taskId); }
    @Override
    public void stop(String taskId) throws Exception {
        Process p = new ProcessBuilder(properties.getDockerExecutable(), "rm", "-f", container(taskId))
                .redirectErrorStream(true).start();
        if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IllegalStateException("专属回测容器清理超时"); }
        String output = new String(p.getInputStream().readNBytes(8192), java.nio.charset.StandardCharsets.UTF_8);
        if (p.exitValue() != 0 && !output.contains("No such container"))
            throw new IllegalStateException("无法确认专属回测容器已停止，请检查 Docker");
    }
    static String readVersion(Path log) throws Exception {
        // Freqtrade 2026.8 export JSON has no version field. Capture its startup banner from this exact run.
        var pattern = java.util.regex.Pattern.compile(".* - freqtrade - INFO - freqtrade ([0-9][A-Za-z0-9.+_-]*)$");
        try (var lines = Files.lines(log)) {
            for (String line : lines.limit(20).toList()) {
                var match = pattern.matcher(line);
                if (match.matches()) return match.group(1);
            }
        }
        throw new IllegalStateException("无法从本次引擎启动记录确认版本");
    }
    static Output readResult(Path archive, String startDate, String endDate, String version) throws Exception {
        if (Files.size(archive) > 25_000_000) throw new IllegalStateException("结果归档过大");
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.stream().filter(e -> e.getName().endsWith(".json") && !e.getName().endsWith(".meta.json")
                    && !e.getName().endsWith("_config.json")).toList();
            JsonNode result = null;
            for (var entry : entries) {
                try (var in = zip.getInputStream(entry)) {
                    byte[] bytes = in.readNBytes(25_000_001);
                    if (bytes.length > 25_000_000) throw new IllegalStateException("结果解压大小超限");
                    JsonNode node = JsonUtils.getObjectMapper().readTree(bytes);
                    if (node.has("strategy")) { if (result != null) throw new IllegalStateException("结果不唯一"); result = node; }
                }
            }
            if (result == null || !result.path("strategy").has(STRATEGY)) throw new IllegalStateException("缺少策略结果");
            JsonNode summary = result.path("strategy").path(STRATEGY);
            for (String field : List.of("total_trades", "profit_total", "profit_total_abs", "max_drawdown_account", "backtest_start_ts", "backtest_end_ts"))
                if (!summary.path(field).isNumber() || !Double.isFinite(summary.path(field).asDouble())) throw new IllegalStateException("结果字段缺失或无效: " + field);
            long start = LocalDate.parse(startDate).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            long end = LocalDate.parse(endDate).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            if (summary.path("backtest_start_ts").asLong() != start || summary.path("backtest_end_ts").asLong() < end - 3600000
                    || summary.path("backtest_end_ts").asLong() > end) throw new IllegalStateException("回测实际区间不符");
            if (!summary.path("total_trades").isIntegralNumber() || summary.path("total_trades").asInt() < 0
                    || !summary.path("trades").isArray() || summary.path("trades").size() != summary.path("total_trades").asInt()) throw new IllegalStateException("成交数不一致");
            if (version == null || version.isBlank()) throw new IllegalStateException("缺少引擎版本证据");
            // The control plane persists our result protocol, not Freqtrade's export schema.
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("schemaVersion", 1);
            normalized.put("totalTrades", summary.path("total_trades").asInt());
            normalized.put("netProfit", summary.path("profit_total_abs").decimalValue());
            normalized.put("returnRatio", summary.path("profit_total").asDouble());
            normalized.put("maxDrawdownRatio", summary.path("max_drawdown_account").asDouble());
            normalized.put("currency", "USDT"); normalized.put("startTime", start); normalized.put("endTimeExclusive", end);
            normalized.put("lastCandleTime", summary.path("backtest_end_ts").asLong());
            List<Map<String, Object>> trades = new ArrayList<>();
            for (JsonNode trade : summary.path("trades")) {
                if (!trade.path("profit_abs").isNumber() || !Double.isFinite(trade.path("profit_abs").asDouble())
                        || !"BTC/USDT".equals(trade.path("pair").asText())) throw new IllegalStateException("成交字段无效");
                trades.add(Map.of("instrument", trade.path("pair").asText(), "openedAt", trade.path("open_date").asText(),
                        "closedAt", trade.path("close_date").asText(), "netProfit", trade.path("profit_abs").decimalValue(),
                        "exitReason", trade.path("exit_reason").asText()));
            }
            normalized.put("trades", trades);
            return new Output(version, JsonUtils.toJsonString(normalized), DatasetRegistry.hash(Files.readAllBytes(archive)));
        }
    }
}
