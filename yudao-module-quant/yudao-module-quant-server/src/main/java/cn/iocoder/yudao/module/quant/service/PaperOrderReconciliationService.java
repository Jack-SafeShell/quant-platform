package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.dal.PaperExecutionRepository;
import cn.iocoder.yudao.module.quant.dal.PaperOrderRepository;
import cn.iocoder.yudao.module.quant.engine.DatasetRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

@Service
public class PaperOrderReconciliationService {
    private final PaperExecutionRepository executions; private final PaperOrderRepository orders;
    public PaperOrderReconciliationService(PaperExecutionRepository executions,PaperOrderRepository orders){this.executions=executions;this.orders=orders;}

    @Transactional public Map<String,Object> reconcile(long tenant,long owner,String execution){
        var task=executions.get(tenant,owner,execution);if(task==null)throw new IllegalArgumentException("模拟盘执行任务不存在");
        var preview=executions.preview(execution);long now=System.currentTimeMillis();
        if(preview==null)return failed(tenant,owner,execution,now,"尚未生成受控工作目录");
        var read=PaperTelemetryReader.readOrders(Path.of((String)preview.get("workDirectory")));
        if(!read.available())return failed(tenant,owner,execution,now,read.error());
        for(var source:read.orders()){String key=DatasetRegistry.hash((execution+"\n"+source.sourceOrderId()).getBytes(StandardCharsets.UTF_8));orders.upsert(execution,tenant,owner,key,source,now);}
        orders.markMissingUnknown(execution,now);
        String hash=DatasetRegistry.hash(JsonUtils.toJsonByte(Map.of("executionId",execution,"sourceOrders",read.orders(),"reconciledAt",now)));
        orders.reconciliation(execution,tenant,owner,read.orders().size(),"PASSED",hash,null,now);
        return orders.reconciliations(tenant,owner,execution).getFirst();
    }
    public List<Map<String,Object>> orders(long tenant,long owner,String execution){requireOwner(tenant,owner,execution);return orders.orders(tenant,owner,execution);}
    public List<Map<String,Object>> reconciliations(long tenant,long owner,String execution){requireOwner(tenant,owner,execution);return orders.reconciliations(tenant,owner,execution);}
    private Map<String,Object> failed(long tenant,long owner,String execution,long now,String message){String hash=DatasetRegistry.hash((execution+"\nFAILED\n"+now).getBytes(StandardCharsets.UTF_8));orders.reconciliation(execution,tenant,owner,0,"FAILED",hash,message,now);return orders.reconciliations(tenant,owner,execution).getFirst();}
    private void requireOwner(long tenant,long owner,String execution){if(executions.get(tenant,owner,execution)==null)throw new IllegalArgumentException("模拟盘执行任务不存在");}
}
