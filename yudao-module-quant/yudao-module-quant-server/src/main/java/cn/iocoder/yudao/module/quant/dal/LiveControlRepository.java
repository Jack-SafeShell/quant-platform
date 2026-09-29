package cn.iocoder.yudao.module.quant.dal;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.util.*;

@Repository
public class LiveControlRepository {
    private final JdbcTemplate jdbc;
    public LiveControlRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public String createOrGet(long tenant, long owner, String reportId, String reportHash, String version,
                              String exchange, String pair, BigDecimal maxOrder, BigDecimal maxDaily,
                              BigDecimal maxExposure, int maxOpenOrders) {
        var existing = byReport(tenant, owner, reportId);
        if (existing != null) return (String) existing.get("id");
        String id = UUID.randomUUID().toString(); long now = System.currentTimeMillis();
        try {
            jdbc.update("INSERT INTO quant_live_control_policy(id,tenant_id,owner_id,admission_report_id,admission_report_hash,policy_version,exchange_name,pair_symbol,trading_mode,max_order_notional,max_daily_notional,max_total_exposure,max_open_orders,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, tenant, owner, reportId, reportHash, version, exchange, pair, "spot", maxOrder, maxDaily, maxExposure, maxOpenOrders, "HALTED", now, now);
            audit(id, tenant, owner, owner, "POLICY_CREATED", null, "HALTED", "离线安全策略已创建；真实执行保持关闭");
            return id;
        } catch (DuplicateKeyException ignored) {
            return (String) Objects.requireNonNull(byReport(tenant, owner, reportId)).get("id");
        }
    }

    public Map<String,Object> get(long tenant,long owner,String id) { return one("SELECT id,admission_report_id AS admissionReportId,admission_report_hash AS admissionReportHash,policy_version AS policyVersion,exchange_name AS exchangeName,pair_symbol AS pairSymbol,trading_mode AS tradingMode,max_order_notional AS maxOrderNotional,max_daily_notional AS maxDailyNotional,max_total_exposure AS maxTotalExposure,max_open_orders AS maxOpenOrders,status,created_at AS createdAt,updated_at AS updatedAt FROM quant_live_control_policy WHERE tenant_id=? AND owner_id=? AND id=?",tenant,owner,id); }
    public Map<String,Object> getForUpdate(long tenant,long owner,String id) { return one("SELECT id,admission_report_id AS admissionReportId,admission_report_hash AS admissionReportHash,policy_version AS policyVersion,exchange_name AS exchangeName,pair_symbol AS pairSymbol,trading_mode AS tradingMode,max_order_notional AS maxOrderNotional,max_daily_notional AS maxDailyNotional,max_total_exposure AS maxTotalExposure,max_open_orders AS maxOpenOrders,status,created_at AS createdAt,updated_at AS updatedAt FROM quant_live_control_policy WHERE tenant_id=? AND owner_id=? AND id=? FOR UPDATE",tenant,owner,id); }
    public Map<String,Object> byReport(long tenant,long owner,String reportId) { return one("SELECT id FROM quant_live_control_policy WHERE tenant_id=? AND owner_id=? AND admission_report_id=?",tenant,owner,reportId); }
    public List<Map<String,Object>> list(long tenant,long owner) { return jdbc.queryForList("SELECT id,admission_report_id AS admissionReportId,admission_report_hash AS admissionReportHash,policy_version AS policyVersion,exchange_name AS exchangeName,pair_symbol AS pairSymbol,trading_mode AS tradingMode,max_order_notional AS maxOrderNotional,max_daily_notional AS maxDailyNotional,max_total_exposure AS maxTotalExposure,max_open_orders AS maxOpenOrders,status,created_at AS createdAt,updated_at AS updatedAt FROM quant_live_control_policy WHERE tenant_id=? AND owner_id=? ORDER BY created_at DESC",tenant,owner); }
    public boolean arm(String id) { return jdbc.update("UPDATE quant_live_control_policy SET status='ARMED_OFFLINE',updated_at=? WHERE id=? AND status='HALTED'",System.currentTimeMillis(),id)==1; }
    public boolean halt(String id) { return jdbc.update("UPDATE quant_live_control_policy SET status='HALTED',updated_at=? WHERE id=? AND status<>'HALTED'",System.currentTimeMillis(),id)==1; }
    public List<Map<String,Object>> armed(){return jdbc.queryForList("SELECT id FROM quant_live_control_policy WHERE status='ARMED_OFFLINE'");}

    public Map<String,Object> decision(long tenant,long owner,String clientOrderId) { return one("SELECT id,policy_id AS policyId,client_order_id AS clientOrderId,request_hash AS requestHash,side,order_type AS orderType,price,amount,notional,current_exposure AS currentExposure,daily_executed_notional AS dailyExecutedNotional,open_orders AS openOrders,decision,reason_code AS reasonCode,reason_message AS reasonMessage,executed,created_at AS createdAt FROM quant_live_order_decision WHERE tenant_id=? AND owner_id=? AND client_order_id=?",tenant,owner,clientOrderId); }
    public Map<String,Object> decisionById(long tenant,long owner,String id) { return one("SELECT id,policy_id AS policyId,client_order_id AS clientOrderId,request_hash AS requestHash,side,order_type AS orderType,price,amount,notional,current_exposure AS currentExposure,daily_executed_notional AS dailyExecutedNotional,open_orders AS openOrders,decision,reason_code AS reasonCode,reason_message AS reasonMessage,executed,created_at AS createdAt FROM quant_live_order_decision WHERE tenant_id=? AND owner_id=? AND id=?",tenant,owner,id); }
    public void decision(String id,String policy,long tenant,long owner,String clientOrderId,String requestHash,String side,String orderType,BigDecimal price,BigDecimal amount,BigDecimal notional,BigDecimal exposure,BigDecimal daily,int openOrders,String decision,String reasonCode,String reasonMessage) {
        jdbc.update("INSERT INTO quant_live_order_decision(id,policy_id,tenant_id,owner_id,client_order_id,request_hash,side,order_type,price,amount,notional,current_exposure,daily_executed_notional,open_orders,decision,reason_code,reason_message,executed,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?)",id,policy,tenant,owner,clientOrderId,requestHash,side,orderType,price,amount,notional,exposure,daily,openOrders,decision,reasonCode,reasonMessage,System.currentTimeMillis());
    }
    public List<Map<String,Object>> decisions(long tenant,long owner,String policy) { return jdbc.queryForList("SELECT id,client_order_id AS clientOrderId,request_hash AS requestHash,side,order_type AS orderType,price,amount,notional,current_exposure AS currentExposure,daily_executed_notional AS dailyExecutedNotional,open_orders AS openOrders,decision,reason_code AS reasonCode,reason_message AS reasonMessage,executed,created_at AS createdAt FROM quant_live_order_decision WHERE tenant_id=? AND owner_id=? AND policy_id=? ORDER BY created_at DESC LIMIT 100",tenant,owner,policy); }
    public void audit(String policy,long tenant,long owner,long actor,String event,String from,String to,String message) { jdbc.update("INSERT INTO quant_live_control_audit(id,policy_id,tenant_id,owner_id,actor_id,event_type,from_status,to_status,message,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),policy,tenant,owner,actor,event,from,to,message,System.currentTimeMillis()); }
    public List<Map<String,Object>> audits(long tenant,long owner,String policy) { return jdbc.queryForList("SELECT id,actor_id AS actorId,event_type AS eventType,from_status AS fromStatus,to_status AS toStatus,message,created_at AS createdAt FROM quant_live_control_audit WHERE tenant_id=? AND owner_id=? AND policy_id=? ORDER BY created_at",tenant,owner,policy); }
    private Map<String,Object> one(String sql,Object...args) { var rows=jdbc.queryForList(sql,args); return rows.isEmpty()?null:rows.getFirst(); }
}
