package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.module.quant.api.backtest.PaperStartTokenRequest;
import cn.iocoder.yudao.module.quant.dal.PaperExecutionRepository;
import cn.iocoder.yudao.module.quant.engine.DatasetRegistry;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

@Service public class PaperStartTokenService {
 private final PaperExecutionRepository repo;private final QuantProperties properties;private final SecureRandom random=new SecureRandom();
 public PaperStartTokenService(PaperExecutionRepository repo,QuantProperties properties){this.repo=repo;this.properties=properties;}
 @Transactional public Map<String,Object> issue(long tenant,long owner,String executionId,PaperStartTokenRequest request){var task=waiting(repo.getForUpdate(tenant,owner,executionId));if(properties.isPaperExecutionEnabled())throw new IllegalArgumentException("签发阶段要求执行总开关关闭");if(!"CONFIRM_DRY_RUN_START".equals(request.confirmation()))throw new IllegalArgumentException("二次确认语不正确");var preview=verifiedPreview(executionId,request.previewHash());long now=System.currentTimeMillis(),expires=now+properties.getPaperStartTokenTtlSeconds()*1000L;repo.expireTokens(executionId,now);repo.revokeTokens(executionId);byte[] bytes=new byte[32];random.nextBytes(bytes);String raw=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),tokenHash=hash(raw),id=UUID.randomUUID().toString();repo.startToken(id,executionId,(String)preview.get("previewHash"),tokenHash,request.comment(),owner,now,expires);repo.audit(executionId,owner,"START_TOKEN_ISSUED","WAITING_ENABLE","WAITING_ENABLE","二次确认已记录；一次性令牌已签发，未启动容器");Map<String,Object> result=new LinkedHashMap<>();result.put("id",id);result.put("executionId",task.get("id"));result.put("previewHash",preview.get("previewHash"));result.put("token",raw);result.put("status","ISSUED");result.put("issuedAt",now);result.put("expiresAt",expires);result.put("singleDisplay",true);result.put("executionStarted",false);return result;}
 @Transactional public Map<String,Object> latest(long tenant,long owner,String executionId){if(repo.get(tenant,owner,executionId)==null)throw new IllegalArgumentException("模拟盘执行任务不存在");repo.expireTokens(executionId,System.currentTimeMillis());var token=repo.latestToken(executionId);if(token==null)throw new IllegalArgumentException("尚未签发启动令牌");return token;}
 @Transactional public boolean consumeForStart(long tenant,long owner,String executionId,String previewHash,String rawToken){waiting(tenant,owner,executionId);if(!properties.isPaperExecutionEnabled())throw new IllegalArgumentException("执行总开关未开启");verifiedPreview(executionId,previewHash);long now=System.currentTimeMillis();repo.expireTokens(executionId,now);if(!repo.consumeToken(executionId,previewHash,hash(rawToken),now))throw new IllegalArgumentException("启动令牌无效、过期或已消费");repo.audit(executionId,owner,"START_TOKEN_CONSUMED","WAITING_ENABLE","WAITING_ENABLE","一次性启动令牌已消费");return true;}
 private Map<String,Object> waiting(long tenant,long owner,String executionId){return waiting(repo.get(tenant,owner,executionId));}
 private Map<String,Object> waiting(Map<String,Object> task){if(task==null)throw new IllegalArgumentException("模拟盘执行任务不存在");if(!"WAITING_ENABLE".equals(task.get("status")))throw new IllegalArgumentException("仅待启用任务可操作启动令牌");return task;}
 private Map<String,Object> verifiedPreview(String executionId,String requestedHash){var preview=repo.preview(executionId);if(preview==null)throw new IllegalArgumentException("请先生成安全命令预览");String stored=(String)preview.get("previewHash"),actual=DatasetRegistry.hash(((String)preview.get("previewJson")).getBytes(StandardCharsets.UTF_8));if(!Objects.equals(stored,requestedHash)||!Objects.equals(stored,actual))throw new IllegalArgumentException("命令预览摘要不一致");return preview;}
 private static String hash(String value){return DatasetRegistry.hash(value.getBytes(StandardCharsets.UTF_8));}
}
