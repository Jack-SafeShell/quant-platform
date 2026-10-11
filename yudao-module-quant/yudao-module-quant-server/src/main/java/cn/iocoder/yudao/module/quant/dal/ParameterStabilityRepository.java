package cn.iocoder.yudao.module.quant.dal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class ParameterStabilityRepository {
    private final JdbcTemplate jdbc;
    private static final String FIELDS="id,request_hash AS requestHash,plan_json AS planJson,task_ids_json AS taskIdsJson,created_at AS createdAt";
    public ParameterStabilityRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Map<String,Object> byKey(long tenant,long owner,String key){
        var rows=jdbc.queryForList("SELECT "+FIELDS+" FROM quant_parameter_stability WHERE tenant_id=? AND owner_id=? AND request_key=?",tenant,owner,key);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public Map<String,Object> get(long tenant,long owner,String id){
        var rows=jdbc.queryForList("SELECT "+FIELDS+" FROM quant_parameter_stability WHERE tenant_id=? AND owner_id=? AND id=?",tenant,owner,id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public List<Map<String,Object>> list(long tenant,long owner){
        return jdbc.queryForList("SELECT "+FIELDS+" FROM quant_parameter_stability WHERE tenant_id=? AND owner_id=? ORDER BY created_at DESC,id DESC LIMIT 50",tenant,owner);
    }
    public void insert(String id,long tenant,long owner,String key,String hash,String plan,String tasks){
        jdbc.update("INSERT INTO quant_parameter_stability(id,tenant_id,owner_id,request_key,request_hash,plan_json,task_ids_json,created_at) VALUES(?,?,?,?,?,?,?,?)",id,tenant,owner,key,hash,plan,tasks,System.currentTimeMillis());
    }
}
