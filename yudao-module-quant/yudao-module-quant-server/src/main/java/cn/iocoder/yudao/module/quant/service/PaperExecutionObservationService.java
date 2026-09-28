package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.dal.PaperExecutionRepository;
import cn.iocoder.yudao.module.quant.engine.DatasetRegistry;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.springframework.stereotype.Service;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class PaperExecutionObservationService {
    private static final int MAX_LOG_BYTES = 65_536;
    private final PaperExecutionRepository repo;
    private final QuantProperties properties;

    public PaperExecutionObservationService(PaperExecutionRepository repo, QuantProperties properties) {
        this.repo = repo;
        this.properties = properties;
    }

    public Map<String, Object> observe(long tenant, long owner, String executionId, int requestedLines) throws Exception {
        var task = repo.get(tenant, owner, executionId);
        if (task == null) throw new IllegalArgumentException("模拟盘执行任务不存在");
        int lines = Math.max(20, Math.min(requestedLines, 200));
        Path root = Path.of(properties.getWorkspace()).toAbsolutePath().normalize();
        Path expectedWork = root.resolve("paper").resolve(executionId).normalize();
        var preview = repo.preview(executionId);
        List<Map<String, Object>> checks = new ArrayList<>();
        check(checks, "EXECUTION_SWITCH_CLOSED", !properties.isPaperExecutionEnabled(), "人工验收前保持 false");
        check(checks, "STATUS_OBSERVABLE", Set.of("WAITING_ENABLE", "STARTING", "RUNNING", "STOPPED", "FAILED").contains(task.get("status")), String.valueOf(task.get("status")));
        check(checks, "PREVIEW_EXISTS", preview != null, preview == null ? "命令预览尚未生成" : String.valueOf(preview.get("previewHash")));
        boolean workMatches = preview != null && expectedWork.toString().equals(Path.of((String) preview.get("workDirectory")).toAbsolutePath().normalize().toString());
        check(checks, "ISOLATED_WORK_DIRECTORY", workMatches, expectedWork.toString());
        boolean previewHashValid = preview != null && Objects.equals(preview.get("previewHash"), DatasetRegistry.hash(((String) preview.get("previewJson")).getBytes(StandardCharsets.UTF_8)));
        check(checks, "PREVIEW_HASH_VALID", previewHashValid, previewHashValid ? "SHA-256 匹配" : "摘要缺失或不匹配");

        Path config = expectedWork.resolve("config.json"), strategy = expectedWork.resolve("strategies/QuantEmaBaseline.py");
        boolean regularFiles = workMatches && Files.isDirectory(expectedWork, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(expectedWork) && !Files.isSymbolicLink(expectedWork.resolve("strategies"))
                && Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(strategy, LinkOption.NOFOLLOW_LINKS);
        check(checks, "IMMUTABLE_INPUT_FILES", regularFiles, regularFiles ? "配置与策略文件存在且不是符号链接" : "文件缺失或路径不安全");
        boolean hashesMatch = false, dryRun = false, commandSafe = false;
        double initialBalance = 0.0;
        if (regularFiles && previewHashValid) {
            var manifest = JsonUtils.getObjectMapper().readTree((String) preview.get("previewJson"));
            hashesMatch = manifest.path("configHash").asText().equals(DatasetRegistry.hash(Files.readAllBytes(config)))
                    && manifest.path("strategyHash").asText().equals(DatasetRegistry.hash(Files.readAllBytes(strategy)));
            var configJson = JsonUtils.getObjectMapper().readTree(Files.readString(config, StandardCharsets.UTF_8));
            initialBalance = configJson.path("dry_run_wallet").asDouble(0.0);
            dryRun = configJson.path("dry_run").asBoolean(false) && "spot".equals(configJson.path("trading_mode").asText())
                    && !configJson.path("api_server").path("enabled").asBoolean(false)
                    && !configJson.path("telegram").path("enabled").asBoolean(false);
            List<String> command = new ArrayList<>();
            manifest.path("command").forEach(node -> command.add(node.asText()));
            commandSafe = command.size() >= 10 && properties.getDockerExecutable().equals(command.getFirst())
                    && "run".equals(command.get(1)) && command.contains(properties.getImage()) && command.contains("trade")
                    && command.contains("--read-only") && command.contains("--cap-drop=ALL")
                    && Objects.equals(task.get("container_name"), manifest.path("containerName").asText())
                    && !manifest.path("executed").asBoolean(true) && !manifest.path("portsPublished").asBoolean(true)
                    && !manifest.path("credentialsIncluded").asBoolean(true)
                    && command.stream().noneMatch(value -> value.equals("--privileged") || value.contains("docker.sock") || value.equals("-p") || value.equals("--publish"));
        }
        check(checks, "INPUT_HASHES_VALID", hashesMatch, hashesMatch ? "配置与策略摘要匹配" : "摘要尚未验证");
        check(checks, "DRY_RUN_ONLY", dryRun, dryRun ? "dry_run=true；spot；API/Telegram 关闭" : "配置尚未验证");
        check(checks, "COMMAND_CONSTRAINTS", commandSafe, commandSafe ? "固定镜像与受限 trade 命令" : "命令尚未验证");

        Path log = expectedWork.resolve("runtime.log");
        boolean logExists = workMatches && Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS);
        String recentLog = logExists ? tail(log, 2_000) : "";
        String logTail = lastLines(recentLog, lines);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executionId", executionId);
        result.put("status", task.get("status"));
        result.put("containerName", task.get("container_name"));
        result.put("executionEnabled", properties.isPaperExecutionEnabled());
        result.put("preflightPassed", checks.stream().allMatch(item -> Boolean.TRUE.equals(item.get("passed"))));
        result.put("checks", checks);
        result.put("logExists", logExists);
        result.put("logTail", logTail);
        result.put("logTruncated", logExists && Files.size(log) > MAX_LOG_BYTES);
        result.put("runtime", runtime(recentLog, String.valueOf(task.get("status"))));
        result.put("portfolio", PaperTelemetryReader.read(expectedWork, initialBalance));
        result.put("observedAt", System.currentTimeMillis());
        return result;
    }

    private static void check(List<Map<String, Object>> checks, String id, boolean passed, String evidence) {
        checks.add(Map.of("id", id, "passed", passed, "evidence", evidence));
    }

    private static String tail(Path path, int maxLines) throws Exception {
        byte[] bytes;
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            long start = Math.max(0, file.length() - MAX_LOG_BYTES);
            file.seek(start);
            bytes = new byte[(int) (file.length() - start)];
            file.readFully(bytes);
        }
        return lastLines(new String(bytes, StandardCharsets.UTF_8).replace("\u0000", ""), maxLines);
    }

    private static String lastLines(String text, int maxLines) {
        String[] rows = text.split("\\R", -1);
        return String.join(System.lineSeparator(), Arrays.copyOfRange(rows, Math.max(0, rows.length - maxLines), rows.length));
    }

    private static Map<String, Object> runtime(String log, String status) {
        Instant started = null, heartbeat = null, market = null;
        int networkErrors = 0, fatalErrors = 0;
        for (String line : log.split("\\R")) {
            Instant timestamp = timestamp(line);
            if (line.contains("Changing state to: RUNNING") && started == null) started = timestamp;
            if (line.contains("Bot heartbeat") && timestamp != null) heartbeat = timestamp;
            if ((line.contains("Wallets synced.") || line.contains("Whitelist with") || line.contains("ohlcv"))
                    && !line.contains(" ERROR ") && timestamp != null) market = timestamp;
            if (line.contains(" ERROR ") && (line.contains("Network") || line.contains("connection") || line.contains("connect"))) networkErrors++;
            if (line.contains("Fatal exception") || line.contains("Configuration error:")) fatalErrors++;
        }
        long soakSeconds = started != null && heartbeat != null && !heartbeat.isBefore(started)
                ? Duration.between(started, heartbeat).toSeconds() : 0;
        long heartbeatAgeSeconds = heartbeat == null ? -1 : Math.max(0, Duration.between(heartbeat, Instant.now()).toSeconds());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("engineRunningSeen", started != null);
        result.put("runningStartedAt", epoch(started));
        result.put("lastHeartbeatAt", epoch(heartbeat));
        result.put("lastMarketDataAt", epoch(market));
        result.put("networkErrorCount", networkErrors);
        result.put("fatalErrorCount", fatalErrors);
        result.put("soakSeconds", soakSeconds);
        result.put("heartbeatAgeSeconds", heartbeatAgeSeconds);
        result.put("soakPassed", started != null && soakSeconds >= 60 && networkErrors == 0 && fatalErrors == 0);
        result.put("currentHealthy", "RUNNING".equals(status) && heartbeatAgeSeconds >= 0 && heartbeatAgeSeconds <= 90
                && networkErrors == 0 && fatalErrors == 0);
        return result;
    }

    private static Instant timestamp(String line) {
        if (line.length() < 23) return null;
        try {
            return LocalDateTime.parse(line.substring(0, 23), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS"))
                    .toInstant(ZoneOffset.UTC);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Long epoch(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }
}
