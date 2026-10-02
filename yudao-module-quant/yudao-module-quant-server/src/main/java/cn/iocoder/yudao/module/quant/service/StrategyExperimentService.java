package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.*;
import cn.iocoder.yudao.module.quant.dal.*;
import cn.iocoder.yudao.module.quant.engine.DatasetRegistry;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Service
public class StrategyExperimentService {
    private final StrategyExperimentRepository repo;
    private final BacktestRepository backtests;
    private final OptimizationService optimizations;
    private final TransactionTemplate transaction;
    public StrategyExperimentService(StrategyExperimentRepository repo, BacktestRepository backtests, OptimizationService optimizations, PlatformTransactionManager manager) {
        this.repo = repo; this.backtests = backtests; this.optimizations = optimizations; this.transaction = new TransactionTemplate(manager);
    }
    public String create(long tenant, long owner, StrategyExperimentRequest request) {
        var versions = request.strategyVersionIds();
        if (versions == null || versions.size() < 2 || versions.size() > 5 || versions.stream().anyMatch(Objects::isNull) || versions.stream().distinct().count() != versions.size())
            throw new IllegalArgumentException("请选择 2 至 5 个不同策略版本");
        var canonical = new TreeMap<String, Object>();
        canonical.put("versions", versions.stream().sorted().toList()); canonical.put("parameters", request.parameterSetId());
        canonical.put("dataset", request.datasetId()); canonical.put("trainStart", request.trainStart()); canonical.put("splitDate", request.splitDate()); canonical.put("validationEnd", request.validationEnd());
        String hash = DatasetRegistry.hash(JsonUtils.toJsonByte(canonical));
        var previous = repo.byKey(tenant, owner, request.requestKey());
        if (previous != null) return sameRequest(previous, hash);
        try {
            return transaction.execute(status -> {
                if (backtests.pending(tenant, owner) + versions.size() * 2 > 10) throw new IllegalArgumentException("实验会超过 10 个待处理任务上限");
                if (backtests.findParameterSet(tenant, owner, request.parameterSetId()) == null) throw new IllegalArgumentException("资金参数集不存在或无权访问");
                for (String version : versions) if (backtests.findVersion(tenant, owner, version) == null) throw new IllegalArgumentException("策略版本不存在或无权访问");
                String id = UUID.randomUUID().toString(); repo.create(id, tenant, owner, request, hash);
                try {
                    for (String version : versions.stream().sorted().toList()) {
                        String batch = optimizations.createSingleParameter(tenant, owner, new OptimizationRequest(version, request.datasetId(), request.trainStart(), request.splitDate(), request.validationEnd(), List.of(request.parameterSetId())));
                        repo.member(id, version, batch);
                    }
                } catch (RuntimeException e) { throw e; }
                catch (Exception e) { throw new IllegalArgumentException("创建策略实验失败：" + e.getMessage(), e); }
                return id;
            });
        } catch (DuplicateKeyException e) {
            var existing = repo.byKey(tenant, owner, request.requestKey());
            if (existing == null) throw e;
            return sameRequest(existing, hash);
        }
    }
    public Map<String, Object> costs(long tenant, long owner, String id, int feeBps, int slippageBps) {
        if (feeBps < 0 || feeBps > 100 || slippageBps < 0 || slippageBps > 100)
            throw new IllegalArgumentException("手续费和滑点须为 0～100 个基点的整数");
        var experiment = get(tenant, owner, id);
        var parameters = JsonUtils.getObjectMapper().readTree((String) experiment.get("parametersJson"));
        var balance = parameters.path("startingBalance").decimalValue();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var member : repo.members(id)) {
            var batch = optimizations.get(tenant, owner, (String) member.get("batchId"));
            for (var value : (List<?>) batch.get("members")) {
                var task = (Map<?, ?>) value;
                if (!"VALIDATION".equals(task.get("phase"))) continue;
                var row = new LinkedHashMap<String, Object>();
                row.put("strategyVersionId", member.get("strategyVersionId")); row.put("taskId", task.get("taskId"));
                row.put("status", task.get("status"));
                if ("SUCCEEDED".equals(task.get("status"))) {
                    var owned = backtests.find(tenant, owner, (String) task.get("taskId"));
                    row.putAll(TradeCostSensitivity.calculate((String) owned.get("resultJson"), balance, feeBps, slippageBps));
                    row.put("artifactHash", owned.get("artifactHash"));
                } else { row.put("available", false); row.put("reason", "VALIDATION_NOT_SUCCEEDED"); }
                rows.add(row);
            }
        }
        var evidence = new TreeMap<String, Object>();
        evidence.put("experimentId", id); evidence.put("model", "FIXED_TRADE_CASHFLOW_V1"); evidence.put("rows", rows);
        evidence.put("feeBps", feeBps); evidence.put("slippageBps", slippageBps); evidence.put("startingBalance", balance);
        evidence.put("datasetId", experiment.get("datasetId")); evidence.put("validationStart", experiment.get("splitDate"));
        evidence.put("validationEnd", experiment.get("validationEnd")); evidence.put("baseFeeRate", parameters.path("fee").decimalValue());
        evidence.put("limitations", List.of("原成交序列固定，不重跑交易信号、ROI 或资金约束", "买入价上调、卖出价下调；按调整后成交金额计算每边手续费",
                "回撤只按已平仓权益计算，不含持仓浮亏，与原逐 K 线回撤口径不同", "不改变原排名、研究审批或运行参数"));
        var result = new LinkedHashMap<String, Object>(evidence);
        result.put("evidenceHash", DatasetRegistry.hash(JsonUtils.toJsonByte(evidence))); result.put("autoApplied", false);
        return result;
    }
    private static String sameRequest(Map<String, Object> previous, String hash) {
        if (!hash.equals(previous.get("requestHash"))) throw new IllegalArgumentException("同一请求标识已用于不同实验条件");
        return (String) previous.get("id");
    }
    public List<Map<String, Object>> list(long tenant, long owner) {
        return repo.list(tenant, owner).stream().map(row -> { row.remove("requestHash"); return row; }).toList();
    }
    @SuppressWarnings("unchecked")
    public Map<String, Object> get(long tenant, long owner, String id) {
        var experiment = repo.get(tenant, owner, id);
        if (experiment == null) throw new IllegalArgumentException("策略实验不存在");
        experiment.remove("requestHash");
        var parameter = backtests.findParameterSet(tenant, owner, (String) experiment.get("parameterSetId"));
        experiment.put("parametersJson", parameter.get("parametersJson"));
        var rows = new ArrayList<Map<String, Object>>(); boolean terminal = true;
        for (var member : repo.members(id)) {
            String batchId = (String) member.get("batchId"), versionId = (String) member.get("strategyVersionId");
            var batch = optimizations.get(tenant, owner, batchId);
            var version = backtests.findVersion(tenant, owner, versionId);
            var row = new LinkedHashMap<String, Object>(); row.put("strategyVersionId", versionId); row.put("batchId", batchId);
            row.put("configuration", StrategyTemplates.readConfiguration((String) version.get("sourceCode")));
            var members = (List<Map<String, Object>>) batch.get("members");
            for (var task : members) row.put("TRAIN".equals(task.get("phase")) ? "trainStatus" : "validationStatus", task.get("status"));
            boolean complete = Boolean.TRUE.equals(batch.get("terminal")); terminal &= complete;
            var ranking = (List<Map<String, Object>>) batch.get("ranking");
            if (!ranking.isEmpty()) for (String key : List.of("trainReturn", "validationReturn", "overfitGap", "validationDrawdown", "validationTrades")) row.put(key, ranking.getFirst().get(key));
            var admission = (Map<String, Object>) batch.get("paperAdmission");
            row.put("paperEligible", admission.get("eligible"));
            String evidence = (String) ((Map<?, ?>) batch.get("researchDraft")).get("evidenceSha256");
            String decision = "PENDING";
            for (var review : (List<Map<String, Object>>) batch.get("reviews")) if (evidence.equals(review.get("evidenceHash"))) decision = (String) review.get("decision");
            row.put("researchDecision", decision); row.putAll(repo.latestPaper(tenant, owner, batchId));
            rows.add(row);
        }
        if (terminal) {
            rows.sort(Comparator.comparingDouble(row -> -((Number) Optional.ofNullable(row.get("validationReturn")).orElse(Double.NEGATIVE_INFINITY)).doubleValue()));
            int rank = 0; for (var row : rows) row.put("rank", row.get("validationReturn") == null ? null : ++rank);
        }
        experiment.put("terminal", terminal && !rows.isEmpty()); experiment.put("rows", rows); experiment.put("autoSelected", false);
        return experiment;
    }
}
