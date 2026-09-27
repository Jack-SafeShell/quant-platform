package cn.iocoder.yudao.module.quant.dal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository public class PaperExecutionRepository {
 private final JdbcTemplate jdbc; public PaperExecutionRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public Map<String,Object> bySession(long tenant,long owner,String session){var rows=jdbc.queryForList("SELECT * FROM quant_paper_execution WHERE tenant_id=? AND owner_id=? AND session_id=?",tenant,owner,session);return rows.isEmpty()?null:rows.getFirst();}
 public void create(String id,long tenant,long owner,String session,String snapshot,String hash,String container){long now=System.currentTimeMillis();jdbc.update("INSERT INTO quant_paper_execution(id,tenant_id,owner_id,session_id,readiness_snapshot_id,readiness_hash,status,container_name,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",id,tenant,owner,session,snapshot,hash,"WAITING_ENABLE",container,now,now);}
 public Map<String,Object> get(long tenant,long owner,String id){var rows=jdbc.queryForList("SELECT * FROM quant_paper_execution WHERE tenant_id=? AND owner_id=? AND id=?",tenant,owner,id);return rows.isEmpty()?null:rows.getFirst();}
 public List<Map<String,Object>> list(long tenant,long owner){return jdbc.queryForList("SELECT * FROM quant_paper_execution WHERE tenant_id=? AND owner_id=? ORDER BY created_at DESC LIMIT 50",tenant,owner);}
 public boolean stop(String id){return jdbc.update("UPDATE quant_paper_execution SET status='STOPPED',updated_at=? WHERE id=? AND status IN ('WAITING_ENABLE','STARTING','RUNNING','STOP_REQUESTED')",System.currentTimeMillis(),id)==1;}
 public void audit(String execution,long actor,String event,String from,String to,String message){jdbc.update("INSERT INTO quant_paper_execution_audit(id,execution_id,actor_id,event_type,from_status,to_status,message,created_at) VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),execution,actor,event,from,to,message,System.currentTimeMillis());}
 public List<Map<String,Object>> audits(String execution){return jdbc.queryForList("SELECT id,event_type AS eventType,from_status AS fromStatus,to_status AS toStatus,message,created_at AS createdAt FROM quant_paper_execution_audit WHERE execution_id=? ORDER BY created_at",execution);}
}
