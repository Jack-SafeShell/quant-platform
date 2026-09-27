package cn.iocoder.yudao.module.quant.dal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository public class PaperSessionRepository {
 private final JdbcTemplate jdbc; public PaperSessionRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public Map<String,Object> existing(long tenant,long owner,String batch,String parameter){var rows=jdbc.queryForList("SELECT * FROM quant_paper_session WHERE tenant_id=? AND owner_id=? AND batch_id=? AND parameter_set_id=?",tenant,owner,batch,parameter);return rows.isEmpty()?null:rows.getFirst();}
 public void create(String id,long tenant,long owner,String batch,String version,String parameter,String evidence){long now=System.currentTimeMillis();jdbc.update("INSERT INTO quant_paper_session(id,tenant_id,owner_id,batch_id,strategy_version_id,parameter_set_id,admission_evidence_hash,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",id,tenant,owner,batch,version,parameter,evidence,"PENDING_APPROVAL",now,now);}
 public Map<String,Object> get(long tenant,long owner,String id){var rows=jdbc.queryForList("SELECT * FROM quant_paper_session WHERE tenant_id=? AND owner_id=? AND id=?",tenant,owner,id);return rows.isEmpty()?null:rows.getFirst();}
 public List<Map<String,Object>> list(long tenant,long owner){return jdbc.queryForList("SELECT * FROM quant_paper_session WHERE tenant_id=? AND owner_id=? ORDER BY created_at DESC LIMIT 50",tenant,owner);}
 public boolean transition(String id,String status){return jdbc.update("UPDATE quant_paper_session SET status=?,updated_at=? WHERE id=? AND status='PENDING_APPROVAL'",status,System.currentTimeMillis(),id)==1;}
 public void review(String session,long reviewer,String decision,String comment,String evidence){jdbc.update("INSERT INTO quant_paper_session_review(id,session_id,reviewer_id,decision,comment,admission_evidence_hash,created_at) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),session,reviewer,decision,comment,evidence,System.currentTimeMillis());}
 public List<Map<String,Object>> reviews(String session){return jdbc.queryForList("SELECT id,decision,comment,admission_evidence_hash AS admissionEvidenceHash,created_at AS createdAt FROM quant_paper_session_review WHERE session_id=? ORDER BY created_at",session);}
}
