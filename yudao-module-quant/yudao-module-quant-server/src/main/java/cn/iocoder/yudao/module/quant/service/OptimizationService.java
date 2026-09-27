package cn.iocoder.yudao.module.quant.service;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.*;
import cn.iocoder.yudao.module.quant.dal.*;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
@Service public class OptimizationService {
 private final OptimizationRepository repo; private final BacktestRepository backtests; private final BacktestService service;
 public OptimizationService(OptimizationRepository repo,BacktestRepository backtests,BacktestService service){this.repo=repo;this.backtests=backtests;this.service=service;}
 public String create(long tenant,long owner,OptimizationRequest r)throws Exception{
  LocalDate start=date(r.trainStart()),split=date(r.splitDate()),end=date(r.validationEnd());
  if(ChronoUnit.DAYS.between(start,split)<7||ChronoUnit.DAYS.between(split,end)<7||ChronoUnit.DAYS.between(start,end)>366)throw new IllegalArgumentException("训练集和验证集各至少 7 天，总区间不超过 366 天");
  if(r.parameterSetIds().stream().distinct().count()!=r.parameterSetIds().size())throw new IllegalArgumentException("参数集不可重复");
  if(backtests.pending(tenant,owner)+r.parameterSetIds().size()*2>10)throw new IllegalArgumentException("优化批次会超过待处理任务上限");
  Map<String,tools.jackson.databind.JsonNode> parameters=new LinkedHashMap<>();
  for(String parameter:r.parameterSetIds()){var set=backtests.findParameterSet(tenant,owner,parameter);if(set==null)throw new IllegalArgumentException("参数集不存在或无权访问");parameters.put(parameter,JsonUtils.getObjectMapper().readTree((String)set.get("parametersJson")));}
  String id=UUID.randomUUID().toString();repo.batch(id,tenant,owner,r.datasetId(),r.strategyVersionId(),r.trainStart(),r.splitDate(),r.validationEnd());
  for(String parameter:r.parameterSetIds()){
   var p=parameters.get(parameter);
   for(String phase:List.of("TRAIN","VALIDATION")){String from=phase.equals("TRAIN")?r.trainStart():r.splitDate(),to=phase.equals("TRAIN")?r.splitDate():r.validationEnd();String task=service.create(tenant,owner,new BacktestRequest("opt-"+id.substring(0,8)+"-"+parameter.substring(0,8)+"-"+phase.toLowerCase(),r.strategyVersionId(),parameter,r.datasetId(),from,to,new BigDecimal(p.path("startingBalance").asText()),new BigDecimal(p.path("stakeAmount").asText()),new BigDecimal(p.path("fee").asText())));repo.member(id,parameter,phase,task);}
  }return id;
 }
 public List<Map<String,Object>> list(long t,long o){return repo.list(t,o);}
 public Map<String,Object> get(long t,long o,String id){
  var batch=repo.get(t,o,id);if(batch==null)throw new IllegalArgumentException("优化批次不存在");var members=repo.members(id);batch.put("members",members);
  boolean terminal=members.stream().allMatch(m->Set.of("SUCCEEDED","FAILED").contains(m.get("status")));
  List<Map<String,Object>> ranking=new ArrayList<>();Map<String,Map<String,Map<String,Object>>> grouped=new LinkedHashMap<>();
  for(var member:members)grouped.computeIfAbsent((String)member.get("parameterSetId"),k->new LinkedHashMap<>()).put((String)member.get("phase"),member);
  if(terminal)for(var entry:grouped.entrySet()){Map<String,Object> train=entry.getValue().get("TRAIN"),validation=entry.getValue().get("VALIDATION");Map<String,Object> row=new LinkedHashMap<>();row.put("parameterSetId",entry.getKey());row.put("trainStatus",train.get("status"));row.put("validationStatus",validation.get("status"));
   if("SUCCEEDED".equals(train.get("status"))&&"SUCCEEDED".equals(validation.get("status"))){var a=metrics((String)train.get("resultJson"));var b=metrics((String)validation.get("resultJson"));row.put("trainReturn",a[0]);row.put("validationReturn",b[0]);row.put("overfitGap",a[0]-b[0]);row.put("validationDrawdown",b[1]);row.put("validationTrades",(int)b[2]);}ranking.add(row);}
  ranking.sort(Comparator.comparingDouble(x->-((Number)x.getOrDefault("validationReturn",Double.NEGATIVE_INFINITY)).doubleValue()));for(int i=0;i<ranking.size();i++)ranking.get(i).put("rank",ranking.get(i).containsKey("validationReturn")?i+1:null);
  batch.put("terminal",terminal);batch.put("ranking",ranking);batch.put("autoSelected",false);batch.put("researchDraft",draft(batch,ranking,terminal));batch.put("reviews",repo.reviews(t,o,id));return batch;
 }
 public String review(long tenant,long owner,String id,ResearchReviewRequest request){var batch=get(tenant,owner,id);if(!Boolean.TRUE.equals(batch.get("terminal")))throw new IllegalArgumentException("批次未结束，不能评审");var draft=(Map<?,?>)batch.get("researchDraft");if(!request.evidenceHash().equals(draft.get("evidenceSha256")))throw new IllegalArgumentException("研究证据已变化，请刷新后重新评审");repo.review(id,owner,request.decision(),request.comment(),request.evidenceHash());return id;}
 public BacktestService.ReportFile exportDraft(long tenant,long owner,String id){var batch=get(tenant,owner,id);var draft=(Map<?,?>)batch.get("researchDraft");String text="# 参数优化研究草稿\n\n- 批次：`"+id+"`\n- 规则：`"+draft.get("ruleVersion")+"`\n- 证据 SHA-256：`"+draft.get("evidenceSha256")+"`\n\n## 结论\n\n"+draft.get("conclusion")+"\n\n## 风险\n\n- "+String.join("\n- ",(List<String>)draft.get("risks"))+"\n\n## 人工复核\n\n- "+String.join("\n- ",(List<String>)draft.get("manualChecks"))+"\n\n> 接受评审仅代表研究结论，不会自动修改参数或进入模拟盘。\n";return new BacktestService.ReportFile("research-"+id+".md","text/markdown;charset=UTF-8",text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 private static Map<String,Object> draft(Map<String,Object> batch,List<Map<String,Object>> ranking,boolean terminal){
  Map<String,Object> evidence=new TreeMap<>();evidence.put("batchId",batch.get("id"));evidence.put("datasetId",batch.get("dataset_id"));evidence.put("trainStart",batch.get("train_start"));evidence.put("splitDate",batch.get("split_date"));evidence.put("validationEnd",batch.get("validation_end"));evidence.put("ranking",ranking);
  String hash=cn.iocoder.yudao.module.quant.engine.DatasetRegistry.hash(JsonUtils.toJsonByte(evidence));List<String> risks=new ArrayList<>();String conclusion;
  if(!terminal)conclusion="批次任务尚未全部结束，当前证据不足，不能形成参数结论。";
  else {long failed=ranking.stream().filter(x->!x.containsKey("validationReturn")).count();if(failed>0)risks.add(failed+" 组参数存在失败任务，排序不完整。");var valid=ranking.stream().filter(x->x.containsKey("validationReturn")).toList();if(valid.isEmpty())conclusion="没有同时完成训练和验证的参数组，不能形成结论。";else {var best=valid.getFirst();double validation=((Number)best.get("validationReturn")).doubleValue(),gap=Math.abs(((Number)best.get("overfitGap")).doubleValue());int trades=((Number)best.get("validationTrades")).intValue();conclusion="验证集排名第一的参数集为 "+best.get("parameterSetId")+"，验证收益率为 "+validation+"；该排名仅用于后续人工复核。";if(validation<=0)risks.add("最高验证收益率仍不为正。");if(gap>0.02)risks.add("训练与验证收益率差超过 2 个百分点，存在明显过拟合迹象。");if(trades<10)risks.add("验证集成交少于 10 笔，样本量不足。");}}
  risks.add("仅覆盖单一数据集、交易对和时间切分，不能代表未来表现。");return Map.of("ruleVersion","quant-research-rules/v1","evidenceSha256",hash,"conclusion",conclusion,"risks",risks,"manualChecks",List.of("核对失败任务与日志","检查手续费、滑点和数据覆盖","更换未见数据区间复验"),"autoApplied",false);
 }
 private static double[] metrics(String json){var n=JsonUtils.getObjectMapper().readTree(json);return new double[]{n.path("returnRatio").asDouble(),n.path("maxDrawdownRatio").asDouble(),n.path("totalTrades").asInt()};}
 private static LocalDate date(String s){try{return LocalDate.parse(s);}catch(Exception e){throw new IllegalArgumentException("日期格式错误");}}
}
