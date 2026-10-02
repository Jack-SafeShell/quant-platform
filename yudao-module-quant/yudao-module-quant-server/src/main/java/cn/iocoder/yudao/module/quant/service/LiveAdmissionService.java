package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.LiveAdmissionConfirmationRequest;
import cn.iocoder.yudao.module.quant.dal.LiveAdmissionRepository;
import cn.iocoder.yudao.module.quant.engine.DatasetRegistry;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class LiveAdmissionService {
    private static final Map<String, String> PHRASES = Map.of(
            "EVIDENCE_REVIEW", "CONFIRM_EVIDENCE_REVIEWED",
            "KEY_BOUNDARY_REVIEW", "CONFIRM_KEY_BOUNDARY_ACCEPTED");
    private final LiveAdmissionRepository repository;

    public LiveAdmissionService(LiveAdmissionRepository repository) {
        this.repository = repository;
    }

    public String create(long tenant, long owner) {
        var backtest = repository.latestSuccessfulBacktest(tenant, owner);
        var soak = repository.qualifyingSoak(tenant, owner);
        var orders = repository.qualifyingOrderRehearsal(tenant, owner);
        var riskStop = repository.latestRiskStop(tenant, owner);
        int unresolvedAlerts = repository.unresolvedAlertCount(tenant, owner);
        List<Map<String, Object>> checks = new ArrayList<>();
        check(checks, "HISTORICAL_BACKTEST", backtest != null, backtest == null ? "缺少成功历史回测" : "任务 " + backtest.get("id") + " / 产物 " + backtest.get("artifactHash"));
        check(checks, "FOUR_HOUR_DRY_RUN", soak != null, soak == null ? "缺少至少 240 条且跨度达到 4 小时的无错误快照" : "执行 " + soak.get("executionId") + " / 快照 " + soak.get("snapshotCount"));
        check(checks, "ORDER_RECONCILIATION_REHEARSAL", orders != null, orders == null ? "缺少含失败恢复的订单对账演练" : "执行 " + orders.get("executionId") + " / PASSED " + orders.get("passedCount") + " / FAILED " + orders.get("failedCount"));
        check(checks, "RISK_STOP", riskStop != null, riskStop == null ? "缺少风险精确停机审计" : "执行 " + riskStop.get("executionId") + " / 审计 " + riskStop.get("auditId"));
        check(checks, "NO_UNRESOLVED_ALERTS", unresolvedAlerts == 0, "未解决告警 " + unresolvedAlerts);
        boolean evidenceComplete = checks.stream().allMatch(item -> Boolean.TRUE.equals(item.get("passed")));

        Map<String, Object> keyBoundary = new TreeMap<>();
        keyBoundary.put("configured", false);
        keyBoundary.put("permissions", List.of("READ", "TRADE"));
        keyBoundary.put("withdrawalAllowed", false);
        keyBoundary.put("ipWhitelistRequired", true);
        keyBoundary.put("storage", "专用加密密钥托管；不得进入数据库、源码、日志、聊天或命令行参数");
        keyBoundary.put("rotationDays", 90);
        keyBoundary.put("emergencyRotation", "疑似泄露、人员或主机变更时立即吊销并轮换");
        keyBoundary.put("environmentIsolation", "模拟盘与实盘使用不同密钥；实盘密钥只允许绑定受控出口 IP");

        Map<String, Object> manifest = new TreeMap<>();
        manifest.put("schema", "quant-live-admission/v1");
        manifest.put("evidence", new TreeMap<>(Map.of(
                "backtest", nullable(backtest),
                "dryRunSoak", nullable(soak),
                "orderRehearsal", nullable(orders),
                "riskStop", nullable(riskStop))));
        manifest.put("checks", checks);
        manifest.put("evidenceComplete", evidenceComplete);
        manifest.put("keyBoundary", keyBoundary);
        manifest.put("requiredConfirmations", PHRASES);
        manifest.put("liveTradingAllowed", false);
        manifest.put("activationAllowed", false);
        manifest.put("assessment", evidenceComplete ? "EVIDENCE_COMPLETE" : "NOT_READY");
        String json = JsonUtils.toJsonString(manifest);
        String hash = DatasetRegistry.hash(json.getBytes(StandardCharsets.UTF_8));
        return repository.createOrGet(tenant, owner, json, hash);
    }

    public Map<String, Object> get(long tenant, long owner, String id) {
        var report = repository.get(tenant, owner, id);
        if (report == null) throw new IllegalArgumentException("准入报告不存在");
        var confirmations = repository.confirmations(tenant, owner, id);
        report.put("confirmations", confirmations);
        var types = confirmations.stream().map(item -> String.valueOf(item.get("confirmationType"))).collect(java.util.stream.Collectors.toSet());
        report.put("confirmationState", types.containsAll(PHRASES.keySet()) ? "DOUBLE_CONFIRMED" : "PENDING_CONFIRMATION");
        report.put("liveTradingAllowed", false);
        report.put("activationAllowed", false);
        return report;
    }

    public void requireFixedLiveStrategy(long tenant, long owner, Map<String, Object> report) {
        var evidence = JsonUtils.getObjectMapper().readTree((String) report.get("reportJson"));
        var backtest = evidence.path("evidence").path("backtest");
        String version = backtest.path("strategyVersionId").asText(backtest.path("strategyversionid").asText());
        try {
            String baselineHash = DatasetRegistry.hash(EmaStrategyTemplate.baseline().getBytes(StandardCharsets.UTF_8));
            if (!baselineHash.equals(repository.strategySourceHash(tenant, owner, version)))
                throw new IllegalArgumentException("当前实盘仅支持固定 EMA20/60 基线；可配置版本请用于历史回测及模拟盘");
        } catch (java.io.IOException e) { throw new IllegalStateException("无法加载实盘基线模板", e); }
    }

    public List<Map<String, Object>> list(long tenant, long owner) {
        var reports = repository.list(tenant, owner);
        reports.forEach(report -> {
            String id = (String) report.get("id");
            int count = repository.confirmations(tenant, owner, id).size();
            report.put("confirmationState", count == PHRASES.size() ? "DOUBLE_CONFIRMED" : "PENDING_CONFIRMATION");
            report.put("activationAllowed", false);
        });
        return reports;
    }

    public String confirm(long tenant, long owner, String id, LiveAdmissionConfirmationRequest request) {
        var report = repository.get(tenant, owner, id);
        if (report == null) throw new IllegalArgumentException("准入报告不存在");
        if (!Objects.equals(report.get("reportHash"), request.reportHash())) throw new IllegalArgumentException("准入报告摘要不匹配");
        String expected = PHRASES.get(request.confirmationType());
        if (!Objects.equals(expected, request.confirmationPhrase())) throw new IllegalArgumentException("确认语不匹配");
        try {
            repository.confirm(id, tenant, owner, owner, request.confirmationType(), request.confirmationPhrase(), request.comment().trim(), request.reportHash());
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("该确认步骤已经完成");
        }
        return get(tenant, owner, id).get("confirmationState").toString();
    }

    public BacktestService.ReportFile export(long tenant, long owner, String id) {
        var report = get(tenant, owner, id);
        return new BacktestService.ReportFile("live-admission-" + id + ".json", "application/json;charset=UTF-8", JsonUtils.toJsonByte(report));
    }

    private static Map<String, Object> nullable(Map<String, Object> value) {
        return value == null ? Map.of("available", false) : value;
    }

    private static void check(List<Map<String, Object>> checks, String id, boolean passed, String evidence) {
        checks.add(new TreeMap<>(Map.of("id", id, "passed", passed, "evidence", evidence)));
    }
}
