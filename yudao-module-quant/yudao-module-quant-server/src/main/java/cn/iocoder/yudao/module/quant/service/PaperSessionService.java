package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.module.quant.api.backtest.*;
import cn.iocoder.yudao.module.quant.dal.PaperSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service public class PaperSessionService {
 private final PaperSessionRepository repo; private final OptimizationService optimizations;
 public PaperSessionService(PaperSessionRepository repo,OptimizationService optimizations){this.repo=repo;this.optimizations=optimizations;}
 public String create(long tenant,long owner,PaperSessionRequest request){var batch=optimizations.get(tenant,owner,request.batchId());var admission=(Map<?,?>)batch.get("paperAdmission");requireReady(admission);boolean parameter=((List<Map<String,Object>>)batch.get("ranking")).stream().anyMatch(x->request.parameterSetId().equals(x.get("parameterSetId"))&&x.containsKey("validationReturn"));if(!parameter)throw new IllegalArgumentException("参数集不属于该批次的有效验证结果");var existing=repo.existing(tenant,owner,request.batchId(),request.parameterSetId());if(existing!=null)return (String)existing.get("id");String id=UUID.randomUUID().toString();repo.create(id,tenant,owner,request.batchId(),(String)batch.get("strategy_version_id"),request.parameterSetId(),(String)admission.get("evidenceSha256"));return id;}
 public List<Map<String,Object>> list(long tenant,long owner){var rows=repo.list(tenant,owner);rows.forEach(PaperSessionService::decorate);return rows;}
 public Map<String,Object> get(long tenant,long owner,String id){var session=repo.get(tenant,owner,id);if(session==null)throw new IllegalArgumentException("模拟盘会话不存在");decorate(session);session.put("reviews",repo.reviews(id));return session;}
 @Transactional public String review(long tenant,long owner,String id,PaperSessionReviewRequest request){var session=get(tenant,owner,id);if(!"PENDING_APPROVAL".equals(session.get("status")))throw new IllegalArgumentException("会话已完成启动审批");var batch=optimizations.get(tenant,owner,(String)session.get("batch_id"));var admission=(Map<?,?>)batch.get("paperAdmission");requireReady(admission);String current=(String)admission.get("evidenceSha256");if(!current.equals(session.get("admission_evidence_hash"))||!current.equals(request.admissionEvidenceHash()))throw new IllegalArgumentException("准入证据已变化，请重新创建会话");if(!repo.transition(id,request.decision()))throw new IllegalArgumentException("会话审批状态已变化");repo.review(id,owner,request.decision(),request.comment(),current);return id;}
 private static void requireReady(Map<?,?> admission){if(!Boolean.TRUE.equals(admission.get("eligible")))throw new IllegalArgumentException("模拟盘准入检查未通过");var reviews=(List<Map<String,Object>>)admission.get("reviews");if(reviews.isEmpty())throw new IllegalArgumentException("缺少模拟盘准入人工确认");var latest=reviews.getLast();if(!"READY".equals(latest.get("decision"))||!Objects.equals(admission.get("evidenceSha256"),latest.get("evidenceHash")))throw new IllegalArgumentException("当前准入证据尚未确认 READY");}
 private static void decorate(Map<String,Object> session){session.put("executionEnabled",false);session.put("activationAllowed",false);session.put("nextAction","APPROVED".equals(session.get("status"))?"等待后续独立执行实现":"PENDING_APPROVAL".equals(session.get("status"))?"等待人工启动审批":"会话已拒绝");}
}
