package cn.iocoder.yudao.module.quant.dal;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class LiveAdmissionRepository {
    private final JdbcTemplate jdbc;

    public LiveAdmissionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> latestSuccessfulBacktest(long tenant, long owner) {
        return one("SELECT t.id,t.strategy_version_id AS strategyVersionId,t.parameter_set_id AS parameterSetId,t.dataset_hash AS datasetHash,t.engine_image AS engineImage,r.engine_version AS engineVersion,r.artifact_hash AS artifactHash,t.finished_at AS finishedAt FROM quant_backtest_task t JOIN quant_backtest_result r ON r.task_id=t.id WHERE t.tenant_id=? AND t.owner_id=? AND t.status='SUCCEEDED' ORDER BY t.finished_at DESC LIMIT 1", tenant, owner);
    }

    public String strategySourceHash(long tenant, long owner, String versionId) {
        var values = jdbc.queryForList("SELECT v.source_hash FROM quant_strategy_version v JOIN quant_strategy s ON s.id=v.strategy_id WHERE s.tenant_id=? AND s.owner_id=? AND v.id=?", String.class, tenant, owner, versionId);
        return values.isEmpty() ? null : values.getFirst();
    }

    public Map<String, Object> qualifyingSoak(long tenant, long owner) {
        return one("SELECT s.execution_id AS executionId,COUNT(*) AS snapshotCount,MIN(s.observed_at) AS firstObservedAt,MAX(s.observed_at) AS lastObservedAt,MAX(s.network_error_count) AS networkErrorCount,MAX(s.fatal_error_count) AS fatalErrorCount FROM quant_paper_observation_snapshot s JOIN quant_paper_execution e ON e.id=s.execution_id WHERE s.tenant_id=? AND s.owner_id=? AND e.status='STOPPED' GROUP BY s.execution_id HAVING COUNT(*)>=240 AND MAX(s.observed_at)-MIN(s.observed_at)>=14400000 AND MAX(s.network_error_count)=0 AND MAX(s.fatal_error_count)=0 ORDER BY MAX(s.observed_at) DESC LIMIT 1", tenant, owner);
    }

    public Map<String, Object> qualifyingOrderRehearsal(long tenant, long owner) {
        return one("SELECT execution_id AS executionId,SUM(CASE WHEN reconciliation_status='PASSED' THEN 1 ELSE 0 END) AS passedCount,SUM(CASE WHEN reconciliation_status='FAILED' THEN 1 ELSE 0 END) AS failedCount,MAX(reconciled_at) AS lastReconciledAt FROM quant_paper_order_reconciliation WHERE tenant_id=? AND owner_id=? GROUP BY execution_id HAVING SUM(CASE WHEN reconciliation_status='PASSED' THEN 1 ELSE 0 END)>0 AND SUM(CASE WHEN reconciliation_status='FAILED' THEN 1 ELSE 0 END)>0 ORDER BY MAX(reconciled_at) DESC LIMIT 1", tenant, owner);
    }

    public Map<String, Object> latestRiskStop(long tenant, long owner) {
        return one("SELECT a.execution_id AS executionId,a.id AS auditId,a.created_at AS stoppedAt FROM quant_paper_execution_audit a JOIN quant_paper_execution e ON e.id=a.execution_id WHERE e.tenant_id=? AND e.owner_id=? AND a.event_type='RISK_STOPPED' AND e.status='STOPPED' ORDER BY a.created_at DESC LIMIT 1", tenant, owner);
    }

    public int unresolvedAlertCount(long tenant, long owner) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM quant_paper_alert WHERE tenant_id=? AND owner_id=? AND status<>'RESOLVED'", Integer.class, tenant, owner);
    }

    public String createOrGet(long tenant, long owner, String json, String hash) {
        var existing = jdbc.queryForList("SELECT id FROM quant_live_admission_report WHERE tenant_id=? AND owner_id=? AND report_hash=?", tenant, owner, hash);
        if (!existing.isEmpty()) return (String) existing.getFirst().get("id");
        String id = UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO quant_live_admission_report(id,tenant_id,owner_id,report_json,report_hash,created_at) VALUES(?,?,?,?,?,?)", id, tenant, owner, json, hash, System.currentTimeMillis());
            return id;
        } catch (DuplicateKeyException ignored) {
            return (String) jdbc.queryForMap("SELECT id FROM quant_live_admission_report WHERE tenant_id=? AND owner_id=? AND report_hash=?", tenant, owner, hash).get("id");
        }
    }

    public Map<String, Object> get(long tenant, long owner, String id) {
        return one("SELECT id,report_json AS reportJson,report_hash AS reportHash,created_at AS createdAt FROM quant_live_admission_report WHERE tenant_id=? AND owner_id=? AND id=?", tenant, owner, id);
    }

    public List<Map<String, Object>> list(long tenant, long owner) {
        return jdbc.queryForList("SELECT id,report_hash AS reportHash,created_at AS createdAt FROM quant_live_admission_report WHERE tenant_id=? AND owner_id=? ORDER BY created_at DESC LIMIT 50", tenant, owner);
    }

    public void confirm(String reportId, long tenant, long owner, long actor, String type, String phrase, String comment, String hash) {
        jdbc.update("INSERT INTO quant_live_admission_confirmation(id,report_id,tenant_id,owner_id,actor_id,confirmation_type,confirmation_phrase,comment,report_hash,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)", UUID.randomUUID().toString(), reportId, tenant, owner, actor, type, phrase, comment, hash, System.currentTimeMillis());
    }

    public List<Map<String, Object>> confirmations(long tenant, long owner, String reportId) {
        return jdbc.queryForList("SELECT id,actor_id AS actorId,confirmation_type AS confirmationType,confirmation_phrase AS confirmationPhrase,comment,report_hash AS reportHash,created_at AS createdAt FROM quant_live_admission_confirmation WHERE tenant_id=? AND owner_id=? AND report_id=? ORDER BY created_at", tenant, owner, reportId);
    }

    private Map<String, Object> one(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
