package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.dal.*;
import cn.iocoder.yudao.module.quant.engine.DatasetRegistry;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service public class PaperReadinessService {
 private final PaperSessionRepository repo; private final PaperSessionService sessions; private final BacktestRepository backtests; private final QuantProperties properties;
 public PaperReadinessService(PaperSessionRepository repo,PaperSessionService sessions,BacktestRepository backtests,QuantProperties properties){this.repo=repo;this.sessions=sessions;this.backtests=backtests;this.properties=properties;}
 public String create(long tenant,long owner,String sessionId)throws Exception{
  var session=sessions.approvedCurrent(tenant,owner,sessionId);var parameter=backtests.findParameterSet(tenant,owner,(String)session.get("parameter_set_id"));if(parameter==null)throw new IllegalArgumentException("参数集不存在或无权访问");var values=JsonUtils.getObjectMapper().readTree((String)parameter.get("parametersJson"));
  Map<String,Object> config=new TreeMap<>();config.put("dryRun",true);config.put("exchange","okx");config.put("pairWhitelist",List.of("BTC/USDT"));config.put("timeframe","1h");config.put("tradingMode","spot");config.put("stakeCurrency","USDT");config.put("stakeAmount",values.path("stakeAmount").asText());config.put("maxOpenTrades",1);config.put("apiServerEnabled",false);config.put("telegramEnabled",false);
  List<Map<String,Object>> checks=new ArrayList<>();check(checks,"SESSION_APPROVED",true,"会话已批准且准入证据仍有效");check(checks,"EXECUTION_DISABLED",!properties.isPaperExecutionEnabled(),"yudao.quant.paper-execution-enabled=false");check(checks,"IMAGE_PINNED",properties.getImage().matches("freqtradeorg/freqtrade@sha256:[0-9a-f]{64}"),properties.getImage());check(checks,"DOCKER_CLI_AVAILABLE",dockerAvailable(),properties.getDockerExecutable()+" --version");check(checks,"WORKSPACE_WRITABLE",workspaceWritable(),properties.getWorkspace());check(checks,"NO_CREDENTIAL_FIELDS",config.keySet().stream().noneMatch(x->x.toLowerCase().matches(".*(key|secret|password|token).*")),"配置仅含公开交易环境与策略运行参数");
  boolean ready=checks.stream().allMatch(x->Boolean.TRUE.equals(x.get("passed")));Map<String,Object> manifest=new TreeMap<>();manifest.put("schema","quant-paper-readiness/v1");manifest.put("sessionId",sessionId);manifest.put("admissionEvidenceHash",session.get("admission_evidence_hash"));manifest.put("strategyVersionId",session.get("strategy_version_id"));manifest.put("parameterSetId",session.get("parameter_set_id"));manifest.put("engineImage",properties.getImage());manifest.put("executionEnabled",false);manifest.put("activationAllowed",false);manifest.put("config",config);manifest.put("checks",checks);manifest.put("ready",ready);String json=JsonUtils.toJsonString(manifest),hash=DatasetRegistry.hash(JsonUtils.toJsonByte(manifest)),id=UUID.randomUUID().toString();repo.readiness(id,sessionId,json,hash,ready);return id;
 }
 public List<Map<String,Object>> list(long tenant,long owner,String sessionId){sessions.get(tenant,owner,sessionId);return repo.readinessSnapshots(sessionId);}
 private boolean dockerAvailable(){try{var p=new ProcessBuilder(properties.getDockerExecutable(),"--version").redirectErrorStream(true).start();return p.waitFor(3,TimeUnit.SECONDS)&&p.exitValue()==0;}catch(Exception e){return false;}}
 private boolean workspaceWritable(){try{Path path=Path.of(properties.getWorkspace()).toAbsolutePath().normalize();Files.createDirectories(path);return Files.isDirectory(path)&&Files.isWritable(path);}catch(Exception e){return false;}}
 private static void check(List<Map<String,Object>> checks,String id,boolean passed,String evidence){checks.add(Map.of("id",id,"passed",passed,"evidence",evidence));}
}
