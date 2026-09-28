package cn.iocoder.yudao.module.quant.dal;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.util.*;

@Repository
public class PaperObservationRepository {
    private final JdbcTemplate jdbc;
    public PaperObservationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void snapshot(String execution, long tenant, long owner, String status, Map<String,Object> runtime,
                         Map<String,Object> portfolio, String evidenceHash, long observedAt) {
        jdbc.update("INSERT INTO quant_paper_observation_snapshot(id,execution_id,tenant_id,owner_id,execution_status,heartbeat_age_seconds,last_heartbeat_at,last_market_data_at,network_error_count,fatal_error_count,soak_seconds,soak_passed,database_available,estimated_available_balance,open_positions,closed_trades,open_orders,total_orders,realized_profit,invested_stake,evidence_hash,observed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), execution, tenant, owner, status, runtime.get("heartbeatAgeSeconds"), runtime.get("lastHeartbeatAt"), runtime.get("lastMarketDataAt"), runtime.get("networkErrorCount"), runtime.get("fatalErrorCount"), runtime.get("soakSeconds"), runtime.get("soakPassed"), portfolio.get("available"), decimal(portfolio.get("estimatedAvailableBalance")), portfolio.get("openPositions"), portfolio.get("closedTrades"), portfolio.get("openOrders"), portfolio.get("totalOrders"), decimal(portfolio.get("realizedProfit")), decimal(portfolio.get("investedStake")), evidenceHash, observedAt);
    }
    public List<Map<String,Object>> snapshots(long tenant,long owner,String execution){return jdbc.queryForList("SELECT id,execution_status AS executionStatus,heartbeat_age_seconds AS heartbeatAgeSeconds,last_heartbeat_at AS lastHeartbeatAt,last_market_data_at AS lastMarketDataAt,network_error_count AS networkErrorCount,fatal_error_count AS fatalErrorCount,soak_seconds AS soakSeconds,soak_passed AS soakPassed,database_available AS databaseAvailable,estimated_available_balance AS estimatedAvailableBalance,open_positions AS openPositions,closed_trades AS closedTrades,open_orders AS openOrders,total_orders AS totalOrders,realized_profit AS realizedProfit,invested_stake AS investedStake,evidence_hash AS evidenceHash,observed_at AS observedAt FROM quant_paper_observation_snapshot WHERE tenant_id=? AND owner_id=? AND execution_id=? ORDER BY observed_at DESC LIMIT 100",tenant,owner,execution);}
    public List<Map<String,Object>> alerts(long tenant,long owner,String execution){return jdbc.queryForList("SELECT id,alert_type AS alertType,severity,status,evidence,first_observed_at AS firstObservedAt,last_observed_at AS lastObservedAt,resolved_at AS resolvedAt FROM quant_paper_alert WHERE tenant_id=? AND owner_id=? AND execution_id=? ORDER BY last_observed_at DESC",tenant,owner,execution);}
    public void raise(String execution,long tenant,long owner,String type,String severity,String evidence){long now=System.currentTimeMillis();int changed=jdbc.update("UPDATE quant_paper_alert SET severity=?,status='OPEN',evidence=?,last_observed_at=?,resolved_at=NULL WHERE execution_id=? AND alert_type=?",severity,evidence,now,execution,type);if(changed==0)try{jdbc.update("INSERT INTO quant_paper_alert(id,execution_id,tenant_id,owner_id,alert_type,severity,status,evidence,first_observed_at,last_observed_at) VALUES(?,?,?,?,?,?,'OPEN',?,?,?)",UUID.randomUUID().toString(),execution,tenant,owner,type,severity,evidence,now,now);}catch(DuplicateKeyException ignored){jdbc.update("UPDATE quant_paper_alert SET severity=?,status='OPEN',evidence=?,last_observed_at=?,resolved_at=NULL WHERE execution_id=? AND alert_type=?",severity,evidence,now,execution,type);}}
    public void resolve(String execution,String type){long now=System.currentTimeMillis();jdbc.update("UPDATE quant_paper_alert SET status='RESOLVED',last_observed_at=?,resolved_at=? WHERE execution_id=? AND alert_type=? AND status='OPEN'",now,now,execution,type);}
    private static BigDecimal decimal(Object value){return value==null?BigDecimal.ZERO:new BigDecimal(String.valueOf(value));}
}
