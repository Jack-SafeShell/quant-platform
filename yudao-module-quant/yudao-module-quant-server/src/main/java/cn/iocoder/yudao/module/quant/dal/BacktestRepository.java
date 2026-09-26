package cn.iocoder.yudao.module.quant.dal;

import cn.iocoder.yudao.module.quant.engine.BacktestEngine;
import cn.iocoder.yudao.module.quant.engine.DatasetRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

/** All interactive reads explicitly scope tenant AND owner; JDBC bypasses MyBatis tenant plugins. */
@Repository
public class BacktestRepository {
    private final JdbcTemplate jdbc;
    public BacktestRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static final String VIEW = """
        SELECT t.id, t.status, t.request_key AS requestKey, t.dataset_id AS datasetId,
          t.dataset_hash AS datasetHash, t.dataset_source AS datasetSource, t.exchange_name AS exchangeName,
          t.engine_image AS engineImage, t.error_message AS errorMessage, t.created_at AS createdAt,
          t.started_at AS startedAt, t.finished_at AS finishedAt, t.request_hash AS requestHash,
          t.tenant_id AS tenantId, t.owner_id AS ownerId,
          t.strategy_version_id AS strategyVersionId, s.name AS strategyName,
          v.source_hash AS strategyHash, v.source_code AS strategySource, p.parameters_json AS parametersJson,
          r.engine_version AS engineVersion, r.artifact_hash AS artifactHash, r.result_json AS resultJson
        FROM quant_backtest_task t
        JOIN quant_strategy_version v ON v.id=t.strategy_version_id
        JOIN quant_strategy s ON s.id=v.strategy_id
        JOIN quant_parameter_set p ON p.id=t.parameter_set_id
        LEFT JOIN quant_backtest_result r ON r.task_id=t.id
        """;
    public Map<String, Object> find(long tenant, long owner, String id) {
        return first(jdbc.queryForList(VIEW + " WHERE t.tenant_id=? AND t.owner_id=? AND t.id=?", tenant, owner, id));
    }
    public Map<String, Object> findByKey(long tenant, long owner, String key) {
        return first(jdbc.queryForList(VIEW + " WHERE t.tenant_id=? AND t.owner_id=? AND t.request_key=?", tenant, owner, key));
    }
    public List<Map<String, Object>> list(long tenant, long owner) {
        return jdbc.queryForList(VIEW + " WHERE t.tenant_id=? AND t.owner_id=? ORDER BY t.created_at DESC LIMIT 100", tenant, owner);
    }
    public int pending(long tenant, long owner) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM quant_backtest_task WHERE tenant_id=? AND owner_id=? AND status IN ('QUEUED','RUNNING')", Integer.class, tenant, owner);
    }
    public void insert(String id, long tenant, long owner, String key, String requestHash, String params,
                       String version, DatasetRegistry.Dataset dataset, String image) {
        String parameter = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO quant_parameter_set (id,parameters_json,parameters_hash) VALUES (?,?,?)", parameter, params, requestHash);
        jdbc.update("""
            INSERT INTO quant_backtest_task
            (id,tenant_id,owner_id,request_key,request_hash,strategy_version_id,parameter_set_id,dataset_id,
             dataset_hash,dataset_source,exchange_name,engine_image,status,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, id, tenant, owner, key, requestHash, version, parameter, dataset.id(), dataset.sha256(), dataset.source(), dataset.exchange(), image, "QUEUED", now);
    }
    public Map<String, Object> findVersion(long tenant, long owner, String id) {
        return first(jdbc.queryForList("""
            SELECT v.id, v.strategy_id AS strategyId, s.name AS strategyName,
              v.source_code AS sourceCode, v.source_hash AS sourceHash
            FROM quant_strategy_version v JOIN quant_strategy s ON s.id=v.strategy_id
            WHERE s.tenant_id=? AND s.owner_id=? AND v.id=?
            """, tenant, owner, id));
    }
    public List<Map<String, Object>> listVersions(long tenant, long owner) {
        return jdbc.queryForList("""
            SELECT v.id, v.strategy_id AS strategyId, s.name AS strategyName, v.source_hash AS sourceHash
            FROM quant_strategy_version v JOIN quant_strategy s ON s.id=v.strategy_id
            WHERE s.tenant_id=? AND s.owner_id=? ORDER BY s.created_at, v.id
            """, tenant, owner);
    }
    public String ensureVersion(long tenant, long owner, String name, String source, String hash) {
        List<String> ids = jdbc.queryForList("""
            SELECT v.id FROM quant_strategy_version v JOIN quant_strategy s ON s.id=v.strategy_id
            WHERE s.tenant_id=? AND s.owner_id=? AND s.name=? AND v.source_hash=? LIMIT 1
            """, String.class, tenant, owner, name, hash);
        if (!ids.isEmpty()) return ids.getFirst();
        String strategy = UUID.randomUUID().toString(), version = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO quant_strategy (id,name,tenant_id,owner_id,created_at) VALUES (?,?,?,?,?)", strategy, name, tenant, owner, now);
        jdbc.update("INSERT INTO quant_strategy_version (id,strategy_id,source_code,source_hash) VALUES (?,?,?,?)", version, strategy, source, hash);
        return version;
    }
    public Map<String, Object> next() {
        return first(jdbc.queryForList(VIEW + " WHERE t.status='QUEUED' ORDER BY t.created_at,t.id LIMIT 1"));
    }
    public List<String> runningIds() {
        return jdbc.queryForList("SELECT id FROM quant_backtest_task WHERE status='RUNNING'", String.class);
    }
    public boolean claim(String id) {
        return jdbc.update("UPDATE quant_backtest_task SET status='RUNNING',started_at=? WHERE id=? AND status='QUEUED'", System.currentTimeMillis(), id) == 1;
    }
    public void complete(String id, BacktestEngine.Output output) {
        jdbc.update("INSERT INTO quant_backtest_result (task_id,engine_version,artifact_hash,result_json) VALUES (?,?,?,?)", id, output.engineVersion(), output.artifactHash(), output.resultJson());
        if (jdbc.update("UPDATE quant_backtest_task SET status='SUCCEEDED',finished_at=? WHERE id=? AND status='RUNNING'", System.currentTimeMillis(), id) != 1)
            throw new IllegalStateException("任务状态已改变");
    }
    public void fail(String id, String error) {
        jdbc.update("UPDATE quant_backtest_task SET status='FAILED',error_message=?,finished_at=? WHERE id=? AND status='RUNNING'", error, System.currentTimeMillis(), id);
    }
    private static Map<String, Object> first(List<Map<String, Object>> rows) { return rows.isEmpty() ? null : rows.getFirst(); }
}
