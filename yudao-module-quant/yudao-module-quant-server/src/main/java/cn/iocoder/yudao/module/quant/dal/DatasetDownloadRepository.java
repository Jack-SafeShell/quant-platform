package cn.iocoder.yudao.module.quant.dal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class DatasetDownloadRepository {
    private final JdbcTemplate jdbc;
    public DatasetDownloadRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Map<String,Object> byRequest(long tenant,long owner,String key) { return one("SELECT * FROM quant_dataset_download_task WHERE tenant_id=? AND owner_id=? AND request_key=?",tenant,owner,key); }
    public Map<String,Object> get(long tenant,long owner,String id) { return one("SELECT * FROM quant_dataset_download_task WHERE tenant_id=? AND owner_id=? AND id=?",tenant,owner,id); }
    public List<Map<String,Object>> list(long tenant,long owner) { return jdbc.queryForList("SELECT * FROM quant_dataset_download_task WHERE tenant_id=? AND owner_id=? ORDER BY created_at DESC LIMIT 100",tenant,owner); }
    public List<Map<String,Object>> audits(long tenant,long owner,String id) { return jdbc.queryForList("SELECT a.event_type AS eventType,a.detail,a.created_at AS createdAt FROM quant_dataset_download_audit a JOIN quant_dataset_download_task t ON t.id=a.task_id WHERE t.tenant_id=? AND t.owner_id=? AND t.id=? ORDER BY a.created_at",tenant,owner,id); }
    public void insert(String id,long tenant,long owner,String key,String hash,String dataset,String start,String end) {insert(id,tenant,owner,key,hash,dataset,start,end,"okx");}
    public void insert(String id,long tenant,long owner,String key,String hash,String dataset,String start,String end,String exchange) {
        long now=System.currentTimeMillis();
        jdbc.update("INSERT INTO quant_dataset_download_task(id,tenant_id,owner_id,request_key,request_hash,dataset_id,start_date,end_date,exchange_name,pair_name,timeframe,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",id,tenant,owner,key,hash,dataset,start,end,exchange,"BTC/USDT","1h","QUEUED",now);
        audit(id,"SUBMITTED","已提交公开历史行情下载",now);
    }
    public Map<String,Object> next(){ return one("SELECT * FROM quant_dataset_download_task WHERE status='QUEUED' ORDER BY created_at LIMIT 1"); }
    public boolean claim(String id){ long now=System.currentTimeMillis(); int n=jdbc.update("UPDATE quant_dataset_download_task SET status='RUNNING',started_at=? WHERE id=? AND status='QUEUED'",now,id); if(n==1)audit(id,"RUNNING","开始下载 OKX BTC/USDT 1h 公开行情",now); return n==1; }
    public void complete(String id,String hash,int candles){ long now=System.currentTimeMillis(); jdbc.update("UPDATE quant_dataset_download_task SET status='SUCCEEDED',dataset_hash=?,candles=?,finished_at=? WHERE id=? AND status='RUNNING'",hash,candles,now,id); audit(id,"SUCCEEDED","数据集已通过完整性校验并发布",now); }
    public void fail(String id,String message){ long now=System.currentTimeMillis(); jdbc.update("UPDATE quant_dataset_download_task SET status='FAILED',error_message=?,finished_at=? WHERE id=? AND status IN ('QUEUED','RUNNING')",message,now,id); audit(id,"FAILED",message,now); }
    public List<String> runningIds(){ return jdbc.queryForList("SELECT id FROM quant_dataset_download_task WHERE status='RUNNING'",String.class); }
    private void audit(String task,String event,String detail,long now){ jdbc.update("INSERT INTO quant_dataset_download_audit(id,task_id,event_type,detail,created_at) VALUES(?,?,?,?,?)",UUID.randomUUID().toString(),task,event,detail,now); }
    private Map<String,Object> one(String sql,Object...args){ var rows=jdbc.queryForList(sql,args); return rows.isEmpty()?null:rows.getFirst(); }
}
