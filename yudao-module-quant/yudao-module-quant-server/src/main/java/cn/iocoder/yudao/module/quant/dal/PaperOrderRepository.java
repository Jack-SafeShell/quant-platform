package cn.iocoder.yudao.module.quant.dal;

import cn.iocoder.yudao.module.quant.service.PaperTelemetryReader.SourceOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class PaperOrderRepository {
    private final JdbcTemplate jdbc;
    public PaperOrderRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public void upsert(String execution,long tenant,long owner,String key,SourceOrder source,long now){
        var rows=jdbc.queryForList("SELECT id,order_status FROM quant_paper_order WHERE execution_id=? AND source_order_id=?",execution,source.sourceOrderId());
        if(rows.isEmpty()){
            String id=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO quant_paper_order(id,execution_id,tenant_id,owner_id,idempotency_key,source_order_id,trade_id,pair_symbol,order_side,order_type,order_status,price,amount,filled,cost,source_created_at,source_updated_at,reconciliation_status,first_seen_at,last_seen_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'MATCHED',?,?)",id,execution,tenant,owner,key,source.sourceOrderId(),source.tradeId(),source.pair(),source.side(),source.orderType(),source.status(),source.price(),source.amount(),source.filled(),source.cost(),source.sourceCreatedAt(),source.sourceUpdatedAt(),now,now);
            audit(id,execution,"DISCOVERED",null,source.status(),"首次从 dry-run SQLite 读取；幂等键 "+key);
            return;
        }
        var row=rows.getFirst();String id=(String)row.get("id"),from=(String)row.get("order_status");
        jdbc.update("UPDATE quant_paper_order SET trade_id=?,pair_symbol=?,order_side=?,order_type=?,order_status=?,price=?,amount=?,filled=?,cost=?,source_created_at=?,source_updated_at=?,reconciliation_status='MATCHED',last_seen_at=? WHERE id=?",source.tradeId(),source.pair(),source.side(),source.orderType(),source.status(),source.price(),source.amount(),source.filled(),source.cost(),source.sourceCreatedAt(),source.sourceUpdatedAt(),now,id);
        if(!Objects.equals(from,source.status()))audit(id,execution,"STATUS_CHANGED",from,source.status(),"dry-run SQLite 状态变化");
    }

    public int markMissingUnknown(String execution,long seenAt){
        var rows=jdbc.queryForList("SELECT id,order_status FROM quant_paper_order WHERE execution_id=? AND last_seen_at<? AND reconciliation_status='MATCHED'",execution,seenAt);
        for(var row:rows){String id=(String)row.get("id"),from=(String)row.get("order_status");jdbc.update("UPDATE quant_paper_order SET order_status='UNKNOWN',reconciliation_status='UNKNOWN' WHERE id=?",id);audit(id,execution,"SOURCE_MISSING",from,"UNKNOWN","本轮完整读取未发现该订单，禁止推断为成交或撤销");}
        return rows.size();
    }
    public void reconciliation(String execution,long tenant,long owner,int sourceCount,String status,String hash,String error,long now){int ledger=jdbc.queryForObject("SELECT COUNT(*) FROM quant_paper_order WHERE execution_id=?",Integer.class,execution);int unknown=jdbc.queryForObject("SELECT COUNT(*) FROM quant_paper_order WHERE execution_id=? AND (order_status='UNKNOWN' OR reconciliation_status='UNKNOWN')",Integer.class,execution);jdbc.update("INSERT INTO quant_paper_order_reconciliation(id,execution_id,tenant_id,owner_id,source_order_count,ledger_order_count,unknown_order_count,reconciliation_status,evidence_hash,error_message,reconciled_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),execution,tenant,owner,sourceCount,ledger,unknown,status,hash,error,now);}
    public List<Map<String,Object>> orders(long tenant,long owner,String execution){return jdbc.queryForList("SELECT id,idempotency_key AS idempotencyKey,source_order_id AS sourceOrderId,trade_id AS tradeId,pair_symbol AS pairSymbol,order_side AS orderSide,order_type AS orderType,order_status AS orderStatus,price,amount,filled,cost,source_created_at AS sourceCreatedAt,source_updated_at AS sourceUpdatedAt,reconciliation_status AS reconciliationStatus,first_seen_at AS firstSeenAt,last_seen_at AS lastSeenAt FROM quant_paper_order WHERE tenant_id=? AND owner_id=? AND execution_id=? ORDER BY first_seen_at DESC",tenant,owner,execution);}
    public List<Map<String,Object>> reconciliations(long tenant,long owner,String execution){return jdbc.queryForList("SELECT id,source_order_count AS sourceOrderCount,ledger_order_count AS ledgerOrderCount,unknown_order_count AS unknownOrderCount,reconciliation_status AS reconciliationStatus,evidence_hash AS evidenceHash,error_message AS errorMessage,reconciled_at AS reconciledAt FROM quant_paper_order_reconciliation WHERE tenant_id=? AND owner_id=? AND execution_id=? ORDER BY reconciled_at DESC LIMIT 100",tenant,owner,execution);}
    private void audit(String order,String execution,String event,String from,String to,String evidence){jdbc.update("INSERT INTO quant_paper_order_audit(id,order_id,execution_id,event_type,from_status,to_status,evidence,created_at) VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),order,execution,event,from,to,evidence,System.currentTimeMillis());}
}
