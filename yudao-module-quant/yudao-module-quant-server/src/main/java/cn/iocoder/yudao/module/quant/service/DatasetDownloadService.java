package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.DatasetDownloadRequest;
import cn.iocoder.yudao.module.quant.dal.DatasetDownloadRepository;
import cn.iocoder.yudao.module.quant.engine.DatasetRegistry;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class DatasetDownloadService {
    private final DatasetDownloadRepository repository; private final QuantProperties properties; private final TransactionTemplate tx;
    public DatasetDownloadService(DatasetDownloadRepository repository,QuantProperties properties,PlatformTransactionManager manager){this.repository=repository;this.properties=properties;this.tx=new TransactionTemplate(manager);}
    public String create(long tenant,long owner,DatasetDownloadRequest request){
        if(!properties.isEnabled())throw new IllegalArgumentException("量化历史任务未启用");
        LocalDate start=parse(request.startDate()),end=parse(request.endDate());
        if(!end.isAfter(start)||ChronoUnit.DAYS.between(start,end)>366||end.atStartOfDay(ZoneOffset.UTC).toInstant().isAfter(Instant.now()))throw new IllegalArgumentException("日期范围须为已结束的 1 至 366 个完整 UTC 日");
        String hash=DatasetRegistry.hash(JsonUtils.toJsonByte(List.of(request.datasetId(),request.startDate(),request.endDate(),"okx","BTC/USDT","1h")));
        Map<String,Object> existing=repository.byRequest(tenant,owner,request.requestKey());
        if(existing!=null){if(!hash.equals(existing.get("request_hash")))throw new IllegalArgumentException("请求标识已用于不同参数");return (String)existing.get("id");}
        if(Files.exists(Path.of(properties.getWorkspace()).toAbsolutePath().resolve("datasets").resolve(request.datasetId())))throw new IllegalArgumentException("数据集编号已存在且不可覆盖");
        String id=UUID.randomUUID().toString();
        try{tx.executeWithoutResult(s->repository.insert(id,tenant,owner,request.requestKey(),hash,request.datasetId(),request.startDate(),request.endDate()));}
        catch(DuplicateKeyException e){existing=repository.byRequest(tenant,owner,request.requestKey());if(existing!=null&&hash.equals(existing.get("request_hash")))return (String)existing.get("id");throw new IllegalArgumentException("数据集编号已被占用");}
        return id;
    }
    public List<Map<String,Object>> list(long tenant,long owner){return repository.list(tenant,owner);}
    public Map<String,Object> get(long tenant,long owner,String id){var row=repository.get(tenant,owner,id);if(row==null)throw new IllegalArgumentException("下载任务不存在");row.put("audits",repository.audits(tenant,owner,id));return row;}
    private static LocalDate parse(String value){try{return LocalDate.parse(value);}catch(Exception e){throw new IllegalArgumentException("日期格式错误");}}
}
