package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.dal.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.*;
import java.util.*;

/** Candidate comparison using persisted dry-run observations, never starts an engine. */
@Service
public class PaperExperimentReviewService {
    private final StrategyExperimentRepository experiments;
    private final BacktestRepository backtests;
    private final JdbcTemplate jdbc;
    public PaperExperimentReviewService(StrategyExperimentRepository experiments,BacktestRepository backtests,JdbcTemplate jdbc){this.experiments=experiments;this.backtests=backtests;this.jdbc=jdbc;}
    public Map<String,Object> get(long tenant,long owner,String id){
        var experiment=experiments.get(tenant,owner,id);
        if(experiment==null)throw new IllegalArgumentException("Experiment does not exist");
        String parameterId=(String)experiment.get("parameterSetId");
        var parameters=JsonUtils.getObjectMapper().readTree((String)backtests.findParameterSet(tenant,owner,parameterId).get("parametersJson"));
        BigDecimal balance=new BigDecimal(parameters.path("startingBalance").asText());
        var rows=new ArrayList<Map<String,Object>>();
        for(var member:experiments.members(id)){
            String version=(String)member.get("strategyVersionId"),batch=(String)member.get("batchId");
            var row=new LinkedHashMap<String,Object>();row.put("strategyVersionId",version);row.put("batchId",batch);
            row.put("configuration",EmaStrategyTemplate.readConfiguration((String)backtests.findVersion(tenant,owner,version).get("sourceCode")));
            var stage=jdbc.queryForList("SELECT s.id AS sessionId,s.status AS sessionStatus,e.id AS executionId,e.status AS executionStatus FROM quant_paper_session s LEFT JOIN quant_paper_execution e ON e.session_id=s.id AND e.tenant_id=s.tenant_id AND e.owner_id=s.owner_id WHERE s.tenant_id=? AND s.owner_id=? AND s.batch_id=? AND s.strategy_version_id=? AND s.parameter_set_id=? ORDER BY s.created_at DESC,s.id DESC,e.created_at DESC,e.id DESC LIMIT 1",tenant,owner,batch,version,parameterId);
            if(!stage.isEmpty()) for(String key:List.of("sessionId","sessionStatus","executionId","executionStatus"))row.put(key,stage.getFirst().get(key));
            String execution=(String)row.get("executionId");
            row.put("telemetryAvailable",false);row.put("realizedProfit",null);row.put("realizedReturnRatio",null);row.put("snapshotCount",0);
            row.put("unresolvedAlerts",0);row.put("reconciliationStatus",null);row.put("unknownOrders",null);
            if(execution!=null){
                var coverage=jdbc.queryForMap("SELECT COUNT(*) AS snapshotCount,MIN(observed_at) AS firstObservedAt,MAX(observed_at) AS lastObservedAt FROM quant_paper_observation_snapshot WHERE tenant_id=? AND owner_id=? AND execution_id=?",tenant,owner,execution);
                for(String key:List.of("snapshotCount","firstObservedAt","lastObservedAt"))row.put(key,coverage.get(key));
                var snapshots=jdbc.queryForList("SELECT id AS snapshotId,observed_at AS observedAt,database_available AS databaseAvailable,closed_trades AS closedTrades,open_positions AS openPositions,open_orders AS openOrders,realized_profit AS realizedProfit,invested_stake AS investedStake,estimated_available_balance AS estimatedAvailableBalance,network_error_count AS networkErrors,fatal_error_count AS fatalErrors,last_heartbeat_at AS lastHeartbeatAt,last_market_data_at AS lastMarketDataAt FROM quant_paper_observation_snapshot WHERE tenant_id=? AND owner_id=? AND execution_id=? ORDER BY observed_at DESC,id DESC LIMIT 1",tenant,owner,execution);
                if(!snapshots.isEmpty()){
                    var snapshot=snapshots.getFirst();Object readable=snapshot.get("databaseAvailable");boolean available=Boolean.TRUE.equals(readable)||(readable instanceof Number n&&n.intValue()==1);
                    for(String key:List.of("snapshotId","observedAt","networkErrors","fatalErrors","lastHeartbeatAt","lastMarketDataAt"))row.put(key,snapshot.get(key));
                    row.put("telemetryAvailable",available);
                    if(available){for(String key:List.of("closedTrades","openPositions","openOrders","realizedProfit","investedStake","estimatedAvailableBalance"))row.put(key,snapshot.get(key));
                        row.put("realizedReturnRatio",balance.signum()>0?new BigDecimal(snapshot.get("realizedProfit").toString()).divide(balance,12,RoundingMode.HALF_UP):null);}
                }
                row.put("unresolvedAlerts",jdbc.queryForObject("SELECT COUNT(*) FROM quant_paper_alert WHERE tenant_id=? AND owner_id=? AND execution_id=? AND status<>'RESOLVED'",Integer.class,tenant,owner,execution));
                var reconciliations=jdbc.queryForList("SELECT reconciliation_status AS reconciliationStatus,unknown_order_count AS unknownOrders,reconciled_at AS reconciledAt FROM quant_paper_order_reconciliation WHERE tenant_id=? AND owner_id=? AND execution_id=? ORDER BY reconciled_at DESC,id DESC LIMIT 1",tenant,owner,execution);
                if(!reconciliations.isEmpty())for(String key:List.of("reconciliationStatus","unknownOrders","reconciledAt"))row.put(key,reconciliations.getFirst().get(key));
            }
            row.put("sampleState",!Boolean.TRUE.equals(row.get("telemetryAvailable"))?"NO_READABLE_TELEMETRY":((Number)row.get("closedTrades")).intValue()==0?"NO_CLOSED_TRADES":"OBSERVED_TRADES");
            rows.add(row);
        }
        return Map.of("experimentId",id,"startingBalance",balance,"rows",rows,"autoSelected",false,"basis","LATEST_PERSISTED_SNAPSHOT_OF_LATEST_EXECUTION","generatedAt",System.currentTimeMillis());
    }
}
