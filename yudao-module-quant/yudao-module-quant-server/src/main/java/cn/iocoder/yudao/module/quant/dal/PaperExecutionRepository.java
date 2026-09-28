package cn.iocoder.yudao.module.quant.dal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository public class PaperExecutionRepository {
 private final JdbcTemplate jdbc; public PaperExecutionRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public Map<String,Object> bySession(long tenant,long owner,String session){var rows=jdbc.queryForList("SELECT * FROM quant_paper_execution WHERE tenant_id=? AND owner_id=? AND session_id=?",tenant,owner,session);return rows.isEmpty()?null:rows.getFirst();}
 public void create(String id,long tenant,long owner,String session,String snapshot,String hash,String container){long now=System.currentTimeMillis();jdbc.update("INSERT INTO quant_paper_execution(id,tenant_id,owner_id,session_id,readiness_snapshot_id,readiness_hash,status,container_name,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",id,tenant,owner,session,snapshot,hash,"WAITING_ENABLE",container,now,now);}
 public Map<String,Object> get(long tenant,long owner,String id){var rows=jdbc.queryForList("SELECT * FROM quant_paper_execution WHERE tenant_id=? AND owner_id=? AND id=?",tenant,owner,id);return rows.isEmpty()?null:rows.getFirst();}
 public Map<String,Object> getForUpdate(long tenant,long owner,String id){var rows=jdbc.queryForList("SELECT * FROM quant_paper_execution WHERE tenant_id=? AND owner_id=? AND id=? FOR UPDATE",tenant,owner,id);return rows.isEmpty()?null:rows.getFirst();}
 public List<Map<String,Object>> list(long tenant,long owner){return jdbc.queryForList("SELECT * FROM quant_paper_execution WHERE tenant_id=? AND owner_id=? ORDER BY created_at DESC LIMIT 50",tenant,owner);}
 public boolean stop(String id){return jdbc.update("UPDATE quant_paper_execution SET status='STOPPED',updated_at=? WHERE id=? AND status IN ('WAITING_ENABLE','STARTING','RUNNING','STOP_REQUESTED')",System.currentTimeMillis(),id)==1;}
 public void audit(String execution,long actor,String event,String from,String to,String message){jdbc.update("INSERT INTO quant_paper_execution_audit(id,execution_id,actor_id,event_type,from_status,to_status,message,created_at) VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),execution,actor,event,from,to,message,System.currentTimeMillis());}
 public List<Map<String,Object>> audits(String execution){return jdbc.queryForList("SELECT id,event_type AS eventType,from_status AS fromStatus,to_status AS toStatus,message,created_at AS createdAt FROM quant_paper_execution_audit WHERE execution_id=? ORDER BY created_at",execution);}
 public Map<String,Object> preview(String execution){var rows=jdbc.queryForList("SELECT id,preview_json AS previewJson,preview_hash AS previewHash,work_directory AS workDirectory,created_at AS createdAt FROM quant_paper_command_preview WHERE execution_id=?",execution);return rows.isEmpty()?null:rows.getFirst();}
 public void preview(String id,String execution,String json,String hash,String directory){jdbc.update("INSERT INTO quant_paper_command_preview(id,execution_id,preview_json,preview_hash,work_directory,created_at) VALUES(?,?,?,?,?,?)",id,execution,json,hash,directory,System.currentTimeMillis());}
 public void expireTokens(String execution,long now){jdbc.update("UPDATE quant_paper_start_token SET status='EXPIRED' WHERE execution_id=? AND status='ISSUED' AND expires_at<?",execution,now);}
 public void revokeTokens(String execution){jdbc.update("UPDATE quant_paper_start_token SET status='REVOKED' WHERE execution_id=? AND status='ISSUED'",execution);}
 public void startToken(String id,String execution,String previewHash,String tokenHash,String comment,long issuer,long issued,long expires){jdbc.update("INSERT INTO quant_paper_start_token(id,execution_id,preview_hash,token_hash,status,confirmation_comment,issued_by,issued_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?)",id,execution,previewHash,tokenHash,"ISSUED",comment,issuer,issued,expires);}
 public Map<String,Object> latestToken(String execution){var rows=jdbc.queryForList("SELECT id,preview_hash AS previewHash,status,confirmation_comment AS confirmationComment,issued_by AS issuedBy,issued_at AS issuedAt,expires_at AS expiresAt,consumed_at AS consumedAt FROM quant_paper_start_token WHERE execution_id=? ORDER BY issued_at DESC LIMIT 1",execution);return rows.isEmpty()?null:rows.getFirst();}
 public boolean consumeToken(String execution,String previewHash,String tokenHash,long now){return jdbc.update("UPDATE quant_paper_start_token SET status='CONSUMED',consumed_at=? WHERE execution_id=? AND preview_hash=? AND token_hash=? AND status='ISSUED' AND expires_at>=?",now,execution,previewHash,tokenHash,now)==1;}
 public boolean starting(String id){return jdbc.update("UPDATE quant_paper_execution SET status='STARTING',error_message=NULL,updated_at=? WHERE id=? AND status='WAITING_ENABLE'",System.currentTimeMillis(),id)==1;}
 public boolean running(String id){return jdbc.update("UPDATE quant_paper_execution SET status='RUNNING',updated_at=? WHERE id=? AND status='STARTING'",System.currentTimeMillis(),id)==1;}
 public boolean failActive(String id,String message){return jdbc.update("UPDATE quant_paper_execution SET status='FAILED',error_message=?,updated_at=? WHERE id=? AND status IN ('STARTING','RUNNING')",message,System.currentTimeMillis(),id)==1;}
 public List<Map<String,Object>> active(){return jdbc.queryForList("SELECT id,tenant_id AS tenantId,owner_id AS ownerId,status,container_name AS containerName FROM quant_paper_execution WHERE status IN ('STARTING','RUNNING')");}
}
