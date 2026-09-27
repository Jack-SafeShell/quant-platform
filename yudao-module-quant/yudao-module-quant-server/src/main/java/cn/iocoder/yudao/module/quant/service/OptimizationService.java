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
 public Map<String,Object> get(long t,long o,String id){var batch=repo.get(t,o,id);if(batch==null)throw new IllegalArgumentException("优化批次不存在");batch.put("members",repo.members(id));return batch;}
 private static LocalDate date(String s){try{return LocalDate.parse(s);}catch(Exception e){throw new IllegalArgumentException("日期格式错误");}}
}
