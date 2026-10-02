package cn.iocoder.yudao.module.quant.dal;

import cn.iocoder.yudao.module.quant.api.backtest.StrategyExperimentRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class StrategyExperimentRepository {
    private final JdbcTemplate jdbc;
    private static final String FIELDS = "id,request_hash AS requestHash,dataset_id AS datasetId,parameter_set_id AS parameterSetId,train_start AS trainStart,split_date AS splitDate,validation_end AS validationEnd,created_at AS createdAt";
    public StrategyExperimentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static Map<String, Object> normalize(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        for (String key : List.of("id", "requestHash", "datasetId", "parameterSetId", "trainStart", "splitDate", "validationEnd", "createdAt")) result.put(key, row.get(key));
        return result;
    }
    public Map<String, Object> byKey(long tenant, long owner, String key) {
        var rows = jdbc.queryForList("SELECT " + FIELDS + " FROM quant_strategy_experiment WHERE tenant_id=? AND owner_id=? AND request_key=?", tenant, owner, key);
        return rows.isEmpty() ? null : normalize(rows.getFirst());
    }
    public Map<String, Object> get(long tenant, long owner, String id) {
        var rows = jdbc.queryForList("SELECT " + FIELDS + " FROM quant_strategy_experiment WHERE tenant_id=? AND owner_id=? AND id=?", tenant, owner, id);
        return rows.isEmpty() ? null : normalize(rows.getFirst());
    }
    public List<Map<String, Object>> list(long tenant, long owner) {
        return jdbc.queryForList("SELECT " + FIELDS + " FROM quant_strategy_experiment WHERE tenant_id=? AND owner_id=? ORDER BY created_at DESC,id DESC LIMIT 50", tenant, owner).stream().map(StrategyExperimentRepository::normalize).toList();
    }
    public void create(String id, long tenant, long owner, StrategyExperimentRequest r, String hash) {
        jdbc.update("INSERT INTO quant_strategy_experiment(id,tenant_id,owner_id,request_key,request_hash,dataset_id,parameter_set_id,train_start,split_date,validation_end,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)", id, tenant, owner, r.requestKey(), hash, r.datasetId(), r.parameterSetId(), r.trainStart(), r.splitDate(), r.validationEnd(), System.currentTimeMillis());
    }
    public void member(String id, String version, String batch) {
        jdbc.update("INSERT INTO quant_strategy_experiment_member(experiment_id,strategy_version_id,optimization_batch_id) VALUES(?,?,?)", id, version, batch);
    }
    public List<Map<String, Object>> members(String id) {
        return jdbc.queryForList("SELECT strategy_version_id AS strategyVersionId,optimization_batch_id AS batchId FROM quant_strategy_experiment_member WHERE experiment_id=? ORDER BY strategy_version_id", id);
    }
    public Map<String, Object> latestPaper(long tenant, long owner, String batch) {
        var rows = jdbc.queryForList("SELECT s.id AS paperSessionId,s.status AS paperSessionStatus,e.id AS paperExecutionId,e.status AS paperExecutionStatus FROM quant_paper_session s LEFT JOIN quant_paper_execution e ON e.session_id=s.id AND e.tenant_id=s.tenant_id AND e.owner_id=s.owner_id WHERE s.tenant_id=? AND s.owner_id=? AND s.batch_id=? ORDER BY s.created_at DESC,e.created_at DESC LIMIT 1", tenant, owner, batch);
        var result = new LinkedHashMap<String, Object>();
        if (!rows.isEmpty()) for (String key : List.of("paperSessionId", "paperSessionStatus", "paperExecutionId", "paperExecutionStatus")) result.put(key, rows.getFirst().get(key));
        return result;
    }
}
