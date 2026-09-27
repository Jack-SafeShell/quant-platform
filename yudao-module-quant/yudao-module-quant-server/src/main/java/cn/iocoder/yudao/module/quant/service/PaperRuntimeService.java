package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.module.quant.api.backtest.*;
import cn.iocoder.yudao.module.quant.dal.PaperExecutionRepository;
import cn.iocoder.yudao.module.quant.engine.PaperProcessLauncher;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;

@Service public class PaperRuntimeService {
 private final PaperExecutionRepository repo;private final PaperExecutionGateService gate;private final PaperProcessLauncher launcher;private final Map<String,PaperProcessLauncher.Handle> handles=new ConcurrentHashMap<>();private ScheduledExecutorService monitor;
 public PaperRuntimeService(PaperExecutionRepository repo,PaperExecutionGateService gate,PaperProcessLauncher launcher){this.repo=repo;this.gate=gate;this.launcher=launcher;}
 public String start(long tenant,long owner,String id,PaperExecutionStartRequest request)throws Exception{var spec=gate.authorize(tenant,owner,id,request);try{var handle=launcher.start(spec.command(),spec.workDirectory(),spec.containerName());handles.put(id,handle);if(!repo.running(id))throw new IllegalArgumentException("任务状态已变化，请刷新");repo.audit(id,owner,"RUNNING","STARTING","RUNNING","dry-run 专属容器已启动并进入监控");return id;}catch(Exception e){handles.remove(id);try{launcher.stop(spec.containerName());}catch(Exception ignored){}if(repo.failActive(id,"dry-run 启动失败；请检查本机运行日志"))repo.audit(id,owner,"START_FAILED","STARTING","FAILED","dry-run 启动失败；未自动重试");throw e;}}
 public String stop(long tenant,long owner,String id,PaperExecutionStopRequest request)throws Exception{var task=repo.get(tenant,owner,id);if(task==null)throw new IllegalArgumentException("模拟盘执行任务不存在");String from=(String)task.get("status");if("STOPPED".equals(from))return id;if("FAILED".equals(from))throw new IllegalArgumentException("终态任务不能停止");if(Set.of("STARTING","RUNNING","STOP_REQUESTED").contains(from))launcher.stop((String)task.get("container_name"));if(!repo.stop(id))throw new IllegalArgumentException("任务状态已变化，请刷新");handles.remove(id);repo.revokeTokens(id);repo.audit(id,owner,"STOPPED",from,"STOPPED",request.comment()+"；已按任务绑定容器名精确停止");return id;}
 @EventListener(ApplicationReadyEvent.class) public void begin(){recoverInterrupted();monitor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"quant-paper-monitor");t.setDaemon(true);return t;});monitor.scheduleWithFixedDelay(this::tick,2,2,TimeUnit.SECONDS);}
 public void tick(){handles.forEach((id,handle)->{if(handle.isAlive())return;handles.remove(id);var task=repo.active().stream().filter(row->id.equals(row.get("id"))).findFirst().orElse(null);if(task!=null&&repo.failActive(id,"dry-run 进程意外退出，退出码 "+handle.exitValue()))repo.audit(id,((Number)task.get("ownerId")).longValue(),"PROCESS_EXITED",(String)task.get("status"),"FAILED","dry-run 进程意外退出；未自动重试");});}
 public void recoverInterrupted(){for(var task:repo.active()){String id=(String)task.get("id");boolean stopped=true;try{launcher.stop((String)task.get("containerName"));}catch(Exception ignored){stopped=false;}String message=stopped?"应用重启中断 dry-run；已请求停止专属容器":"应用重启中断 dry-run；专属容器停止结果未确认";if(repo.failActive(id,message))repo.audit(id,((Number)task.get("ownerId")).longValue(),"RESTART_RECOVERY",(String)task.get("status"),"FAILED",message+"，未自动恢复");}}
 @PreDestroy public void close(){if(monitor!=null)monitor.shutdownNow();try{recoverInterrupted();}catch(Exception ignored){}}
}
