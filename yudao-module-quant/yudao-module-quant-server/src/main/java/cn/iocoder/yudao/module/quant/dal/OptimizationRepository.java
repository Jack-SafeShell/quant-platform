package cn.iocoder.yudao.module.quant.dal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;
@Repository public class OptimizationRepository {
 private final JdbcTemplate jdbc; public OptimizationRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public void batch(String id,long tenant,long owner,String dataset,String version,String start,String split,String end){jdbc.update("INSERT INTO quant_optimization_batch(id,tenant_id,owner_id,dataset_id,strategy_version_id,train_start,split_date,validation_end,created_at) VALUES(?,?,?,?,?,?,?,?,?)",id,tenant,owner,dataset,version,start,split,end,System.currentTimeMillis());}
 public void member(String batch,String parameter,String phase,String task){jdbc.update("INSERT INTO quant_optimization_member(batch_id,parameter_set_id,phase,task_id) VALUES(?,?,?,?)",batch,parameter,phase,task);}
 public Map<String,Object> get(long tenant,long owner,String id){var rows=jdbc.queryForList("SELECT * FROM quant_optimization_batch WHERE tenant_id=? AND owner_id=? AND id=?",tenant,owner,id);return rows.isEmpty()?null:rows.getFirst();}
 public List<Map<String,Object>> members(String id){return jdbc.queryForList("SELECT m.parameter_set_id AS parameterSetId,m.phase,m.task_id AS taskId,t.status,r.result_json AS resultJson FROM quant_optimization_member m JOIN quant_backtest_task t ON t.id=m.task_id LEFT JOIN quant_backtest_result r ON r.task_id=t.id WHERE m.batch_id=? ORDER BY m.parameter_set_id,m.phase",id);}
 public List<Map<String,Object>> list(long tenant,long owner){return jdbc.queryForList("SELECT * FROM quant_optimization_batch WHERE tenant_id=? AND owner_id=? ORDER BY created_at DESC LIMIT 50",tenant,owner);}
 public void review(String batch,long reviewer,String decision,String comment,String hash){jdbc.update("INSERT INTO quant_research_review(id,batch_id,reviewer_id,decision,comment,evidence_hash,created_at) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),batch,reviewer,decision,comment,hash,System.currentTimeMillis());}
 public List<Map<String,Object>> reviews(long tenant,long owner,String batch){return jdbc.queryForList("SELECT r.id,r.decision,r.comment,r.evidence_hash AS evidenceHash,r.created_at AS createdAt FROM quant_research_review r JOIN quant_optimization_batch b ON b.id=r.batch_id WHERE b.tenant_id=? AND b.owner_id=? AND b.id=? ORDER BY r.created_at",tenant,owner,batch);}
}
