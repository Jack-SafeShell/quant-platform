package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.module.quant.api.backtest.PaperExecutionStopRequest;
import cn.iocoder.yudao.module.quant.dal.*;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service public class PaperExecutionService {
 private final PaperExecutionRepository repo; private final PaperSessionRepository sessionRepo; private final PaperSessionService sessions; private final QuantProperties properties;
 public PaperExecutionService(PaperExecutionRepository repo,PaperSessionRepository sessionRepo,PaperSessionService sessions,QuantProperties properties){this.repo=repo;this.sessionRepo=sessionRepo;this.sessions=sessions;this.properties=properties;}
 @Transactional public String create(long tenant,long owner,String sessionId){sessions.approvedCurrent(tenant,owner,sessionId);var snapshot=sessionRepo.latestReadiness(sessionId);if(snapshot==null||!Boolean.TRUE.equals(snapshot.get("ready")))throw new IllegalArgumentException("最新启动前环境校验未通过");var existing=repo.bySession(tenant,owner,sessionId);if(existing!=null)return (String)existing.get("id");String id=UUID.randomUUID().toString(),container="quant-platform-paper-"+id;repo.create(id,tenant,owner,sessionId,(String)snapshot.get("id"),(String)snapshot.get("manifestHash"),container);repo.audit(id,owner,"CREATED",null,"WAITING_ENABLE","执行计划已创建；总开关关闭，未启动容器");return id;}
 public List<Map<String,Object>> list(long tenant,long owner){var rows=repo.list(tenant,owner);rows.forEach(this::decorate);return rows;}
 public Map<String,Object> get(long tenant,long owner,String id){var task=repo.get(tenant,owner,id);if(task==null)throw new IllegalArgumentException("模拟盘执行任务不存在");decorate(task);task.put("audits",repo.audits(id));return task;}
 @Transactional public String stop(long tenant,long owner,String id,PaperExecutionStopRequest request){var task=get(tenant,owner,id);String from=(String)task.get("status");if("STOPPED".equals(from))return id;if(Set.of("FAILED").contains(from))throw new IllegalArgumentException("终态任务不能停止");if(!repo.stop(id))throw new IllegalArgumentException("任务状态已变化，请刷新");repo.revokeTokens(id);repo.audit(id,owner,"STOPPED",from,"STOPPED",request.comment()+"；按任务绑定容器名精确停止，当前阶段未启动容器");return id;}
 private void decorate(Map<String,Object> task){task.put("executionEnabled",properties.isPaperExecutionEnabled());task.put("activationAllowed",false);task.put("containerStarted",false);}
}
