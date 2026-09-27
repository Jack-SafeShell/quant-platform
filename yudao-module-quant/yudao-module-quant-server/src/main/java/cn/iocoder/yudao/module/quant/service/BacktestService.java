package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.BacktestRequest;
import cn.iocoder.yudao.module.quant.api.backtest.ParameterSetRequest;
import cn.iocoder.yudao.module.quant.dal.BacktestRepository;
import cn.iocoder.yudao.module.quant.engine.DatasetRegistry;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class BacktestService {
    public record ReportFile(String filename, String contentType, byte[] content) { }
    private final BacktestRepository repository;
    private final DatasetRegistry datasets;
    private final QuantProperties properties;
    private final TransactionTemplate transaction;
    public BacktestService(BacktestRepository repository, DatasetRegistry datasets, QuantProperties properties, PlatformTransactionManager manager) {
        this.repository = repository; this.datasets = datasets; this.properties = properties; this.transaction = new TransactionTemplate(manager);
    }
    public String create(long tenant, long owner, BacktestRequest request) throws Exception {
        if (!properties.isEnabled()) throw new IllegalArgumentException("回测未启用，请先完成数据和运行环境配置");
        LocalDate start, end;
        try { start = LocalDate.parse(request.startDate()); end = LocalDate.parse(request.endDate()); }
        catch (DateTimeException e) { throw new IllegalArgumentException("请提供有效的 UTC 日期"); }
        if (!end.isAfter(start) || ChronoUnit.DAYS.between(start, end) > 366) throw new IllegalArgumentException("回测区间须为 1 至 366 天，结束日不包含");
        if (request.stakeAmount().compareTo(request.startingBalance()) >= 0) throw new IllegalArgumentException("单笔投入必须小于初始资金");
        String parameterJson = parameterJson(request.startingBalance(), request.stakeAmount(), request.fee());
        Map<String, Object> parameterSet = repository.findParameterSet(tenant, owner, request.parameterSetId());
        if (parameterSet == null || !parameterJson.equals(parameterSet.get("parametersJson")))
            throw new IllegalArgumentException("参数集不存在或与请求参数不一致");
        String params = JsonUtils.toJsonString(request), digest = DatasetRegistry.hash(params.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> existing = repository.findByKey(tenant, owner, request.requestKey());
        if (existing != null) return sameRequest(existing, digest);
        DatasetRegistry.Dataset dataset;
        try { dataset = datasets.load(request.datasetId(), start, end); }
        catch (java.nio.file.NoSuchFileException e) { throw new IllegalArgumentException("数据集尚未准备，请先运行数据准备脚本"); }
        Map<String, Object> version = repository.findVersion(tenant, owner, request.strategyVersionId());
        if (version == null) throw new IllegalArgumentException("策略版本不存在或无权访问");
        String source = (String) version.get("sourceCode");
        String sourceHash = DatasetRegistry.hash(source.getBytes(StandardCharsets.UTF_8));
        if (!sourceHash.equals(version.get("sourceHash"))) throw new IllegalArgumentException("策略版本摘要不符");
        String id = UUID.randomUUID().toString();
        try {
            transaction.executeWithoutResult(status -> {
                if (repository.pending(tenant, owner) >= 10) throw new IllegalArgumentException("待处理任务过多，请等待现有任务完成");
                repository.insert(id, tenant, owner, request.requestKey(), digest, params, request.strategyVersionId(), request.parameterSetId(), dataset, properties.getImage());
            });
            return id;
        } catch (DuplicateKeyException e) {
            existing = repository.findByKey(tenant, owner, request.requestKey());
            if (existing == null) throw e;
            return sameRequest(existing, digest);
        }
    }
    public List<Map<String, Object>> listStrategyVersions(long tenant, long owner) throws Exception {
        String source = new ClassPathResource("quant/QuantEmaBaseline.py").getContentAsString(StandardCharsets.UTF_8);
        String hash = DatasetRegistry.hash(source.getBytes(StandardCharsets.UTF_8));
        transaction.executeWithoutResult(status -> repository.ensureVersion(tenant, owner, "QuantEmaBaseline", source, hash));
        return repository.listVersions(tenant, owner);
    }
    public List<Map<String, Object>> listParameterSets(long tenant, long owner) {
        String json = parameterJson(new java.math.BigDecimal("1000"), new java.math.BigDecimal("100"), new java.math.BigDecimal("0.001"));
        String hash = DatasetRegistry.hash(json.getBytes(StandardCharsets.UTF_8));
        String id = transaction.execute(status -> repository.ensureParameterSet(tenant, owner, json, hash));
        List<Map<String, Object>> result = new ArrayList<>(repository.listParameterSets(tenant, owner));
        if (result.stream().noneMatch(item -> id.equals(item.get("id")))) result.addFirst(repository.findParameterSet(tenant, owner, id));
        return result;
    }
    public String createParameterSet(long tenant, long owner, ParameterSetRequest request) {
        if (request.stakeAmount().compareTo(request.startingBalance()) >= 0)
            throw new IllegalArgumentException("单笔投入必须小于初始资金");
        String json = parameterJson(request.startingBalance(), request.stakeAmount(), request.fee());
        return transaction.execute(status -> repository.ensureParameterSet(tenant, owner, json, DatasetRegistry.hash(json.getBytes(StandardCharsets.UTF_8))));
    }
    public List<Map<String, Object>> compare(long tenant, long owner, List<String> ids) throws Exception {
        if (ids == null || ids.size() < 2 || ids.size() > 5 || ids.stream().distinct().count() != ids.size())
            throw new IllegalArgumentException("请选择 2 至 5 个不同回测任务");
        List<Map<String, Object>> comparison = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> task = get(tenant, owner, id);
            if (!"SUCCEEDED".equals(task.get("status"))) throw new IllegalArgumentException("只能对比已完成任务");
            var result = JsonUtils.getObjectMapper().readTree((String) task.get("resultJson"));
            comparison.add(Map.of("id", id, "strategyName", task.get("strategyName"), "datasetId", task.get("datasetId"),
                    "totalTrades", result.path("totalTrades").asInt(), "netProfit", result.path("netProfit").decimalValue(),
                    "returnRatio", result.path("returnRatio").decimalValue(), "maxDrawdownRatio", result.path("maxDrawdownRatio").decimalValue()));
        }
        return comparison;
    }
    public List<DatasetRegistry.DatasetQuality> listDatasets() throws Exception { return datasets.list(); }
    public ReportFile exportReport(long tenant, long owner, String id, String format) throws Exception {
        Map<String, Object> task = get(tenant, owner, id);
        if (!"SUCCEEDED".equals(task.get("status"))) throw new IllegalArgumentException("只能导出已完成回测报告");
        var request = JsonUtils.getObjectMapper().readTree((String) task.get("parametersJson"));
        var metrics = JsonUtils.getObjectMapper().readTree((String) task.get("resultJson"));
        Map<String, Object> manifest = new TreeMap<>();
        manifest.put("schemaVersion", "quant-backtest-report/v1");
        manifest.put("taskId", id);
        manifest.put("strategy", Map.of("name", task.get("strategyName"), "versionId", task.get("strategyVersionId"), "sha256", task.get("strategyHash")));
        manifest.put("dataset", Map.of("id", task.get("datasetId"), "exchange", task.get("exchangeName"), "sha256", task.get("datasetHash"), "source", task.get("datasetSource")));
        manifest.put("engine", Map.of("image", task.get("engineImage"), "version", task.get("engineVersion"), "artifactSha256", task.get("artifactHash")));
        manifest.put("request", JsonUtils.getObjectMapper().convertValue(request, Map.class));
        manifest.put("metrics", Map.of("totalTrades", metrics.path("totalTrades").asInt(), "netProfit", metrics.path("netProfit").decimalValue(), "returnRatio", metrics.path("returnRatio").decimalValue(), "maxDrawdownRatio", metrics.path("maxDrawdownRatio").decimalValue()));
        manifest.put("notice", "仅为历史回测技术记录，不构成投资建议或盈利证明");
        byte[] canonical = JsonUtils.toJsonByte(manifest);
        manifest.put("manifestSha256", DatasetRegistry.hash(canonical));
        if ("json".equalsIgnoreCase(format)) return new ReportFile("backtest-" + id + ".json", "application/json;charset=UTF-8", JsonUtils.toJsonByte(manifest));
        if (!"md".equalsIgnoreCase(format)) throw new IllegalArgumentException("报告格式仅支持 json 或 md");
        @SuppressWarnings("unchecked") Map<String,Object> metricMap=(Map<String,Object>)manifest.get("metrics");
        String markdown = "# 历史回测实验报告\n\n" +
                "- 任务编号：`" + id + "`\n- 策略：" + task.get("strategyName") + " (`" + task.get("strategyHash") + "`)\n" +
                "- 数据集：" + task.get("datasetId") + " / " + task.get("exchangeName") + " (`" + task.get("datasetHash") + "`)\n" +
                "- 引擎：" + task.get("engineVersion") + " / `" + task.get("engineImage") + "`\n- 产物 SHA-256：`" + task.get("artifactHash") + "`\n\n" +
                "## 可复现参数\n\n```json\n" + JsonUtils.toJsonPrettyString(manifest.get("request")) + "\n```\n\n" +
                "## 核心指标\n\n| 成交数 | 净收益 | 收益率 | 最大回撤 |\n|---:|---:|---:|---:|\n| " + metricMap.get("totalTrades") + " | " + metricMap.get("netProfit") + " | " + metricMap.get("returnRatio") + " | " + metricMap.get("maxDrawdownRatio") + " |\n\n" +
                "清单 SHA-256：`" + manifest.get("manifestSha256") + "`\n\n> 仅为历史回测技术记录，不构成投资建议或盈利证明。\n";
        return new ReportFile("backtest-" + id + ".md", "text/markdown;charset=UTF-8", markdown.getBytes(StandardCharsets.UTF_8));
    }
    private static String parameterJson(java.math.BigDecimal balance, java.math.BigDecimal stake, java.math.BigDecimal fee) {
        return JsonUtils.toJsonString(new TreeMap<>(Map.of("fee", fee, "stakeAmount", stake, "startingBalance", balance)));
    }
    private static String sameRequest(Map<String, Object> existing, String hash) {
        if (!hash.equals(existing.get("requestHash"))) throw new IllegalArgumentException("同一请求标识已用于不同参数");
        return (String) existing.get("id");
    }
    public Map<String, Object> get(long tenant, long owner, String id) {
        Map<String, Object> task = repository.find(tenant, owner, id);
        if (task == null) throw new IllegalArgumentException("回测任务不存在");
        return publicView(task, true);
    }
    public List<Map<String, Object>> list(long tenant, long owner) {
        return repository.list(tenant, owner).stream().map(task -> publicView(task, false)).toList();
    }
    private static Map<String, Object> publicView(Map<String, Object> task, boolean detail) {
        Map<String, Object> result = new LinkedHashMap<>();
        // Explicit whitelist also normalizes JDBC drivers that fold column-label case.
        for (String key : List.of("id", "status", "requestKey", "datasetId", "datasetHash", "datasetSource",
                "exchangeName", "engineImage", "errorMessage", "createdAt", "startedAt", "finishedAt",
                "strategyVersionId", "strategyName", "strategyHash", "parametersJson", "engineVersion", "artifactHash")) result.put(key, task.get(key));
        if (detail) result.put("resultJson", task.get("resultJson"));
        return result;
    }
}
