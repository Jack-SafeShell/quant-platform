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
  batch.put("terminal",terminal);batch.put("ranking",ranking);batch.put("autoSelected",false);return batch;
 }
 private static double[] metrics(String json){var n=JsonUtils.getObjectMapper().readTree(json);return new double[]{n.path("returnRatio").asDouble(),n.path("maxDrawdownRatio").asDouble(),n.path("totalTrades").asInt()};}
 private static LocalDate date(String s){try{return LocalDate.parse(s);}catch(Exception e){throw new IllegalArgumentException("日期格式错误");}}
}
