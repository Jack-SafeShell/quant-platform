package cn.iocoder.yudao.module.quant.dal;

import cn.iocoder.yudao.module.quant.api.backtest.PaperAlertActionRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.*;

@Repository
public class PaperObservationRepository {
    private final JdbcTemplate jdbc;

    public PaperObservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void snapshot(String execution, long tenant, long owner, String status, Map<String,Object> runtime,
                         Map<String,Object> portfolio, String evidenceHash, long observedAt) {
        jdbc.update("INSERT INTO quant_paper_observation_snapshot(id,execution_id,tenant_id,owner_id,execution_status,heartbeat_age_seconds,last_heartbeat_at,last_market_data_at,network_error_count,fatal_error_count,soak_seconds,soak_passed,database_available,estimated_available_balance,open_positions,closed_trades,open_orders,total_orders,realized_profit,invested_stake,evidence_hash,observed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), execution, tenant, owner, status, runtime.get("heartbeatAgeSeconds"), runtime.get("lastHeartbeatAt"), runtime.get("lastMarketDataAt"), runtime.get("networkErrorCount"), runtime.get("fatalErrorCount"), runtime.get("soakSeconds"), runtime.get("soakPassed"), portfolio.get("available"), decimal(portfolio.get("estimatedAvailableBalance")), portfolio.get("openPositions"), portfolio.get("closedTrades"), portfolio.get("openOrders"), portfolio.get("totalOrders"), decimal(portfolio.get("realizedProfit")), decimal(portfolio.get("investedStake")), evidenceHash, observedAt);
    }
    public List<Map<String,Object>> snapshots(long tenant,long owner,String execution){return jdbc.queryForList("SELECT id,execution_status AS executionStatus,heartbeat_age_seconds AS heartbeatAgeSeconds,last_heartbeat_at AS lastHeartbeatAt,last_market_data_at AS lastMarketDataAt,network_error_count AS networkErrorCount,fatal_error_count AS fatalErrorCount,soak_seconds AS soakSeconds,soak_passed AS soakPassed,database_available AS databaseAvailable,estimated_available_balance AS estimatedAvailableBalance,open_positions AS openPositions,closed_trades AS closedTrades,open_orders AS openOrders,total_orders AS totalOrders,realized_profit AS realizedProfit,invested_stake AS investedStake,evidence_hash AS evidenceHash,observed_at AS observedAt FROM quant_paper_observation_snapshot WHERE tenant_id=? AND owner_id=? AND execution_id=? ORDER BY observed_at DESC LIMIT 100",tenant,owner,execution);}

    public List<Map<String,Object>> alerts(long tenant,long owner,String execution){return jdbc.queryForList("SELECT id,alert_type AS alertType,severity,status,evidence,first_observed_at AS firstObservedAt,last_observed_at AS lastObservedAt,acknowledged_at AS acknowledgedAt,acknowledged_by AS acknowledgedBy,resolved_at AS resolvedAt,resolution_comment AS resolutionComment FROM quant_paper_alert WHERE tenant_id=? AND owner_id=? AND execution_id=? ORDER BY last_observed_at DESC",tenant,owner,execution);}

    public List<Map<String,Object>> alertActions(long tenant,long owner,String execution){return jdbc.queryForList("SELECT id,alert_id AS alertId,action_type AS actionType,from_status AS fromStatus,to_status AS toStatus,actor_id AS actorId,comment,created_at AS createdAt FROM quant_paper_alert_action WHERE tenant_id=? AND owner_id=? AND execution_id=? ORDER BY created_at DESC",tenant,owner,execution);}

    public void raise(String execution,long tenant,long owner,String type,String severity,String evidence){
        long now=System.currentTimeMillis();
        int changed=jdbc.update("UPDATE quant_paper_alert SET severity=?,evidence=?,last_observed_at=?,resolved_at=CASE WHEN status='RESOLVED' THEN NULL ELSE resolved_at END,resolution_comment=CASE WHEN status='RESOLVED' THEN NULL ELSE resolution_comment END,acknowledged_at=CASE WHEN status='RESOLVED' THEN NULL ELSE acknowledged_at END,acknowledged_by=CASE WHEN status='RESOLVED' THEN NULL ELSE acknowledged_by END,status=CASE WHEN status='RESOLVED' THEN 'OPEN' ELSE status END WHERE execution_id=? AND alert_type=?",severity,evidence,now,execution,type);
        if(changed==0)try{jdbc.update("INSERT INTO quant_paper_alert(id,execution_id,tenant_id,owner_id,alert_type,severity,status,evidence,first_observed_at,last_observed_at) VALUES(?,?,?,?,?,?,'OPEN',?,?,?)",UUID.randomUUID().toString(),execution,tenant,owner,type,severity,evidence,now,now);}catch(DuplicateKeyException ignored){raise(execution,tenant,owner,type,severity,evidence);}
    }

    public void resolve(String execution,String type){long now=System.currentTimeMillis();jdbc.update("UPDATE quant_paper_alert SET status='RESOLVED',last_observed_at=?,resolved_at=?,resolution_comment='系统检测到异常已恢复' WHERE execution_id=? AND alert_type=? AND status IN ('OPEN','ACKNOWLEDGED')",now,now,execution,type);}

    public String act(long tenant,long owner,String alertId,PaperAlertActionRequest request){
        var rows=jdbc.queryForList("SELECT id,execution_id,status FROM quant_paper_alert WHERE id=? AND tenant_id=? AND owner_id=?",alertId,tenant,owner);
        if(rows.isEmpty())throw new IllegalArgumentException("告警不存在或无权访问");
        var alert=rows.getFirst();String from=String.valueOf(alert.get("status")),to;long now=System.currentTimeMillis();int changed;
        if("ACKNOWLEDGE".equals(request.action())){if(!"OPEN".equals(from))throw new IllegalArgumentException("只有 OPEN 告警可以确认");to="ACKNOWLEDGED";changed=jdbc.update("UPDATE quant_paper_alert SET status=?,acknowledged_at=?,acknowledged_by=? WHERE id=? AND tenant_id=? AND owner_id=? AND status='OPEN'",to,now,owner,alertId,tenant,owner);}
        else if("RESOLVE".equals(request.action())){if(!Set.of("OPEN","ACKNOWLEDGED").contains(from))throw new IllegalArgumentException("告警已经解决");to="RESOLVED";changed=jdbc.update("UPDATE quant_paper_alert SET status=?,resolved_at=?,resolution_comment=? WHERE id=? AND tenant_id=? AND owner_id=? AND status IN ('OPEN','ACKNOWLEDGED')",to,now,request.comment().trim(),alertId,tenant,owner);}
        else throw new IllegalArgumentException("不支持的告警处置动作");
        if(changed!=1)throw new IllegalArgumentException("告警状态已变化，请刷新后重试");
        jdbc.update("INSERT INTO quant_paper_alert_action(id,alert_id,execution_id,tenant_id,owner_id,actor_id,action_type,from_status,to_status,comment,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),alertId,alert.get("execution_id"),tenant,owner,owner,request.action(),from,to,request.comment().trim(),now);
        return to;
    }

    public int purgeTerminalSnapshotsBefore(long cutoff){return jdbc.update("DELETE FROM quant_paper_observation_snapshot WHERE observed_at<? AND execution_id IN (SELECT id FROM quant_paper_execution WHERE status IN ('STOPPED','FAILED'))",cutoff);}

    private static BigDecimal decimal(Object value){return value==null?BigDecimal.ZERO:new BigDecimal(String.valueOf(value));}
}
