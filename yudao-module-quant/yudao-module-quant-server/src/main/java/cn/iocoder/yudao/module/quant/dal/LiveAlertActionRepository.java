package cn.iocoder.yudao.module.quant.dal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class LiveAlertActionRepository {
    private final JdbcTemplate jdbc;
    public LiveAlertActionRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Map<String,Object> alert(long tenant,long owner,String id,boolean lock){return one("SELECT a.id,a.session_id AS sessionId,s.policy_id AS policyId,a.alert_type AS alertType,a.status,a.last_seen_at AS lastSeenAt,a.resolved_at AS resolvedAt FROM quant_live_automation_alert a JOIN quant_live_automation_session s ON s.id=a.session_id AND s.tenant_id=a.tenant_id AND s.owner_id=a.owner_id WHERE a.tenant_id=? AND a.owner_id=? AND a.id=?"+(lock?" FOR UPDATE":""),tenant,owner,id);}
    public Map<String,Object> proof(long tenant,long owner,String alert,String id){return one("SELECT id,evidence_hash AS evidenceHash,evidence_json AS evidenceJson,expires_at AS expiresAt FROM quant_live_automation_alert_action WHERE tenant_id=? AND owner_id=? AND alert_id=? AND id=? AND action_type='CHECK'",tenant,owner,alert,id);}
    public Map<String,Object> resolution(long tenant,long owner,String alert,long resolvedAt){return one("SELECT id,evidence_hash AS evidenceHash,evidence_json AS evidenceJson FROM quant_live_automation_alert_action WHERE tenant_id=? AND owner_id=? AND alert_id=? AND action_type='RESOLVE' AND created_at=?",tenant,owner,alert,resolvedAt);}
    public void append(String id,Map<String,Object> alert,long tenant,long owner,String action,String from,String to,String comment,String hash,String json,long at,Long expires){jdbc.update("INSERT INTO quant_live_automation_alert_action(id,alert_id,session_id,policy_id,tenant_id,owner_id,actor_id,action_type,from_status,to_status,comment,evidence_hash,evidence_json,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,alert.get("id"),alert.get("sessionId"),alert.get("policyId"),tenant,owner,owner,action,from,to,comment,hash,json,at,expires);}
    public boolean resolve(long tenant,long owner,String id,long lastSeen,long at){return jdbc.update("UPDATE quant_live_automation_alert SET status='RESOLVED',resolved_at=? WHERE tenant_id=? AND owner_id=? AND id=? AND status='OPEN' AND last_seen_at=?",at,tenant,owner,id,lastSeen)==1;}
    public int accountRunning(String account){return jdbc.queryForObject("SELECT COUNT(*) FROM quant_live_automation_session s JOIN quant_live_control_policy p ON p.id=s.policy_id WHERE p.account_id=? AND s.status='RUNNING'",Integer.class,account);}
    public List<Map<String,Object>> actions(long tenant,long owner,String session){return jdbc.queryForList("SELECT id,alert_id AS alertId,actor_id AS actorId,action_type AS actionType,from_status AS fromStatus,to_status AS toStatus,comment,evidence_hash AS evidenceHash,created_at AS createdAt,expires_at AS expiresAt FROM quant_live_automation_alert_action WHERE tenant_id=? AND owner_id=? AND session_id=? ORDER BY created_at DESC,id DESC LIMIT 100",tenant,owner,session);}
    private Map<String,Object> one(String sql,Object...args){var rows=jdbc.queryForList(sql,args);return rows.isEmpty()?null:rows.getFirst();}
}
