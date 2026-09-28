package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.dal.*;
import cn.iocoder.yudao.module.quant.engine.DatasetRegistry;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

@Service
public class PaperObservationMonitorService {
    private final PaperExecutionRepository executions; private final PaperExecutionObservationService observations; private final PaperObservationRepository repository; private final QuantProperties properties;
    private ScheduledExecutorService scheduler;
    public PaperObservationMonitorService(PaperExecutionRepository executions,PaperExecutionObservationService observations,PaperObservationRepository repository,QuantProperties properties){this.executions=executions;this.observations=observations;this.repository=repository;this.properties=properties;}
    @EventListener(ApplicationReadyEvent.class) public void begin(){scheduler=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"quant-paper-observation");t.setDaemon(true);return t;});scheduler.scheduleWithFixedDelay(this::tick,5,properties.getPaperObservationIntervalSeconds(),TimeUnit.SECONDS);scheduler.scheduleWithFixedDelay(this::cleanup,10,1,TimeUnit.DAYS);}
    public void tick(){for(var task:executions.active())try{capture(((Number)task.get("tenantId")).longValue(),((Number)task.get("ownerId")).longValue(),(String)task.get("id"));}catch(Exception ignored){}}
    @SuppressWarnings("unchecked") public Map<String,Object> capture(long tenant,long owner,String execution)throws Exception{var result=observations.observe(tenant,owner,execution,20);var runtime=(Map<String,Object>)result.get("runtime");var portfolio=(Map<String,Object>)result.get("portfolio");long observed=((Number)result.get("observedAt")).longValue();String hash=DatasetRegistry.hash(JsonUtils.toJsonByte(Map.of("executionId",execution,"status",result.get("status"),"runtime",runtime,"portfolio",portfolio,"observedAt",observed)));repository.snapshot(execution,tenant,owner,String.valueOf(result.get("status")),runtime,portfolio,hash,observed);boolean running="RUNNING".equals(result.get("status"));long age=((Number)runtime.get("heartbeatAgeSeconds")).longValue();alert(execution,tenant,owner,"STALE_HEARTBEAT","HIGH",running&&(age>90||(age<0&&Boolean.TRUE.equals(runtime.get("engineRunningSeen")))),"最后心跳距今 "+age+" 秒");alert(execution,tenant,owner,"NETWORK_ERROR","WARN",((Number)runtime.get("networkErrorCount")).intValue()>0,"网络错误 "+runtime.get("networkErrorCount")+" 次");alert(execution,tenant,owner,"FATAL_ERROR","HIGH",((Number)runtime.get("fatalErrorCount")).intValue()>0,"致命错误 "+runtime.get("fatalErrorCount")+" 次");boolean riskBreach=number(portfolio.get("openPositions")).compareTo(BigDecimal.valueOf(properties.getPaperMaxOpenPositions()))>0||number(portfolio.get("investedStake")).compareTo(properties.getPaperMaxTotalExposure())>0||number(portfolio.get("realizedProfit")).compareTo(properties.getPaperMaxDailyLoss().negate())<0;alert(execution,tenant,owner,"RISK_LIMIT_BREACH","HIGH",riskBreach,"持仓 "+portfolio.get("openPositions")+" / 暴露 "+portfolio.get("investedStake")+" / 已实现收益 "+portfolio.get("realizedProfit"));return result;}
    public List<Map<String,Object>> snapshots(long tenant,long owner,String execution){requireOwner(tenant,owner,execution);return repository.snapshots(tenant,owner,execution);}
    public List<Map<String,Object>> alerts(long tenant,long owner,String execution){requireOwner(tenant,owner,execution);return repository.alerts(tenant,owner,execution);}
    public List<Map<String,Object>> alertActions(long tenant,long owner,String execution){requireOwner(tenant,owner,execution);return repository.alertActions(tenant,owner,execution);}
    @Transactional public String act(long tenant,long owner,String alertId,cn.iocoder.yudao.module.quant.api.backtest.PaperAlertActionRequest request){return repository.act(tenant,owner,alertId,request);}
    public int cleanup(){long cutoff=System.currentTimeMillis()-TimeUnit.DAYS.toMillis(properties.getPaperSnapshotRetentionDays());return repository.purgeTerminalSnapshotsBefore(cutoff);}
    public void processExit(String execution,long tenant,long owner,String evidence){repository.raise(execution,tenant,owner,"PROCESS_EXITED","HIGH",evidence);}
    private void alert(String execution,long tenant,long owner,String type,String severity,boolean active,String evidence){if(active)repository.raise(execution,tenant,owner,type,severity,evidence);else repository.resolve(execution,type);}
    private void requireOwner(long tenant,long owner,String execution){if(executions.get(tenant,owner,execution)==null)throw new IllegalArgumentException("模拟盘执行任务不存在");}
    private static BigDecimal number(Object value){return value==null?BigDecimal.ZERO:new BigDecimal(String.valueOf(value));}
    @PreDestroy public void close(){if(scheduler!=null)scheduler.shutdownNow();}
}
