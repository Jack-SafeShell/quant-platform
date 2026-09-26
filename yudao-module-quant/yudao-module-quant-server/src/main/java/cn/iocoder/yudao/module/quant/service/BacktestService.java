package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.BacktestRequest;
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
                repository.insert(id, tenant, owner, request.requestKey(), digest, params, request.strategyVersionId(), dataset, properties.getImage());
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
