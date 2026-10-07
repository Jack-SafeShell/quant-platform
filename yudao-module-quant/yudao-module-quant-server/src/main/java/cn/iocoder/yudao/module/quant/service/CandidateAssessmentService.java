package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.CandidateAssessmentRequest;
import cn.iocoder.yudao.module.quant.engine.DatasetRegistry;
import org.springframework.stereotype.Service;
import java.math.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Research screening only; validation windows reset capital and are never compounded. */
@Service
public class CandidateAssessmentService {
    private final StrategyExperimentService experiments;
    public CandidateAssessmentService(StrategyExperimentService experiments){this.experiments=experiments;}
    record Window(Map<String,Object> experiment,Map<String,Object> base,Map<String,Object> stress) {}
    public Map<String,Object> assess(long tenant,long owner,CandidateAssessmentRequest request){
        validate(request);
        var windows=new ArrayList<Window>();
        for(String id:request.experimentIds())windows.add(new Window(experiments.get(tenant,owner,id),experiments.costs(tenant,owner,id,request.feeBps(),request.slippageBps()),experiments.costs(tenant,owner,id,request.stressFeeBps(),request.stressSlippageBps())));
        return evaluate(windows,request);
    }
    static void validate(CandidateAssessmentRequest r){
        if(r.experimentIds()==null||r.experimentIds().size()<2||r.experimentIds().size()>4||r.experimentIds().stream().anyMatch(Objects::isNull)||r.experimentIds().stream().distinct().count()!=r.experimentIds().size())throw new IllegalArgumentException("请选择2至4个不同实验");
        for(Integer cost:Arrays.asList(r.feeBps(),r.slippageBps(),r.stressFeeBps(),r.stressSlippageBps()))if(cost==null||cost<0||cost>100)throw new IllegalArgumentException("费用与滑点必须在0至100基点内");
        if(r.stressFeeBps()<r.feeBps()||r.stressSlippageBps()<r.slippageBps())throw new IllegalArgumentException("压力费用和滑点不得低于基础假设");
    }
    @SuppressWarnings("unchecked")
    static Map<String,Object> evaluate(List<Window> input,CandidateAssessmentRequest request){
        validate(request);
        if(input.size()!=request.experimentIds().size())throw new IllegalArgumentException("实验窗口数量不匹配");
        var windows=input.stream().sorted(Comparator.comparing(w->String.valueOf(w.experiment().get("splitDate")))).toList();
        var first=windows.getFirst().experiment();var versions=index((List<Map<String,Object>>)first.get("rows"));
        if(versions.size()<2||versions.size()>5)throw new IllegalArgumentException("候选数量不匹配");
        var actualIds=new HashSet<String>();LocalDate previousEnd=null;long days=0;var evidenceWindows=new ArrayList<Map<String,Object>>();
        for(var w:windows){
            var e=w.experiment();actualIds.add(String.valueOf(e.get("id")));
            if(!Boolean.TRUE.equals(e.get("terminal"))||!Objects.equals(e.get("datasetId"),first.get("datasetId"))||!Objects.equals(e.get("parameterSetId"),first.get("parameterSetId"))||!Objects.equals(e.get("parametersJson"),first.get("parametersJson"))||!index((List<Map<String,Object>>)e.get("rows")).keySet().equals(versions.keySet()))throw new IllegalArgumentException("仅比较已完成、同数据、同资金和同候选的实验");
            var start=LocalDate.parse(String.valueOf(e.get("splitDate")));var end=LocalDate.parse(String.valueOf(e.get("validationEnd")));
            if(!end.isAfter(start)||previousEnd!=null&&start.isBefore(previousEnd))throw new IllegalArgumentException("验证时段须有效且不重叠");
            previousEnd=end;days+=ChronoUnit.DAYS.between(start,end);
            for(var c:List.of(w.base(),w.stress()))if(!Objects.equals(c.get("experimentId"),e.get("id"))||!Objects.equals(c.get("datasetId"),e.get("datasetId"))||!Objects.equals(c.get("validationStart"),e.get("splitDate"))||!Objects.equals(c.get("validationEnd"),e.get("validationEnd"))||!Objects.equals(c.get("model"),"FIXED_TRADE_CASHFLOW_V1"))throw new IllegalArgumentException("成本证据来源不匹配");
            if(integer(w.base(),"feeBps")!=request.feeBps()||integer(w.base(),"slippageBps")!=request.slippageBps()||integer(w.stress(),"feeBps")!=request.stressFeeBps()||integer(w.stress(),"slippageBps")!=request.stressSlippageBps())throw new IllegalArgumentException("成本假设不匹配");
            evidenceWindows.add(Map.of("experimentId",e.get("id"),"validationStart",start.toString(),"validationEnd",end.toString(),"baseCostHash",w.base().get("evidenceHash"),"stressCostHash",w.stress().get("evidenceHash")));
        }
        if(!actualIds.equals(new HashSet<>(request.experimentIds())))throw new IllegalArgumentException("实验身份不匹配");
        var rows=new ArrayList<Map<String,Object>>();
        for(String version:new TreeSet<>(versions.keySet())){
            var observations=new ArrayList<Map<String,Object>>();BigDecimal worstBase=null,worstStress=null,maxDrawdown=BigDecimal.ZERO;int trades=0,minTrades=Integer.MAX_VALUE,positive=0;
            for(var w:windows){
                var original=index((List<Map<String,Object>>)w.experiment().get("rows")).get(version);var base=index((List<Map<String,Object>>)w.base().get("rows")).get(version);var stress=index((List<Map<String,Object>>)w.stress().get("rows")).get(version);
                if(original==null||base==null||stress==null||!"SUCCEEDED".equals(original.get("trainStatus"))||!"SUCCEEDED".equals(original.get("validationStatus"))||!Boolean.TRUE.equals(base.get("available"))||!Boolean.TRUE.equals(stress.get("available"))||!Objects.equals(base.get("artifactHash"),stress.get("artifactHash"))||base.get("artifactHash")==null||!Objects.equals(base.get("taskId"),stress.get("taskId")))throw new IllegalArgumentException("训练、验证及完整成交成本证据均须成功");
                BigDecimal b=number(base,"adjustedReturn"),s=number(stress,"adjustedReturn"),dd=number(original,"validationDrawdown");int n=integer(base,"tradeCount");
                if(n<0||n!=integer(stress,"tradeCount")||n!=integer(original,"validationTrades")||dd.signum()<0)throw new IllegalArgumentException("成交数或回撤证据不一致");
                worstBase=worstBase==null?b:worstBase.min(b);worstStress=worstStress==null?s:worstStress.min(s);maxDrawdown=maxDrawdown.max(dd);trades+=n;minTrades=Math.min(minTrades,n);if(b.signum()>0)positive++;
                observations.add(Map.of("experimentId",w.experiment().get("id"),"baseReturn",b,"stressReturn",s,"baseDrawdown",dd,"trades",n,"artifactHash",base.get("artifactHash")));
            }
            String classification=minTrades<5?"INSUFFICIENT_TRADES":worstBase.signum()<=0?"INCONSISTENT":worstStress.signum()<=0?"COST_SENSITIVE":"RESEARCH_CANDIDATE";
            var row=new LinkedHashMap<String,Object>();row.put("strategyVersionId",version);row.put("configuration",versions.get(version).get("configuration"));row.put("classification",classification);row.put("positiveWindows",positive);row.put("windowCount",windows.size());row.put("worstBaseReturn",worstBase);row.put("worstStressReturn",worstStress);row.put("maxBaseDrawdown",maxDrawdown);row.put("minWindowTrades",minTrades);row.put("totalTrades",trades);row.put("tradesPer30Days",BigDecimal.valueOf(trades*30L).divide(BigDecimal.valueOf(days),4,RoundingMode.HALF_UP));row.put("windows",observations);rows.add(row);
        }
        rows.sort(Comparator.<Map<String,Object>,Boolean>comparing(r->!"RESEARCH_CANDIDATE".equals(r.get("classification"))).thenComparing(r->number(r,"worstStressReturn"),Comparator.reverseOrder()).thenComparing(r->number(r,"worstBaseReturn"),Comparator.reverseOrder()).thenComparing(r->String.valueOf(r.get("strategyVersionId"))));int rank=0;for(var row:rows)row.put("rank",++rank);
        var evidence=new TreeMap<String,Object>();evidence.put("ruleVersion","CANDIDATE_ASSESSMENT_V1");evidence.put("datasetId",first.get("datasetId"));evidence.put("parameterSetId",first.get("parameterSetId"));evidence.put("parametersJson",first.get("parametersJson"));evidence.put("feeBps",request.feeBps());evidence.put("slippageBps",request.slippageBps());evidence.put("stressFeeBps",request.stressFeeBps());evidence.put("stressSlippageBps",request.stressSlippageBps());evidence.put("minTradesPerWindow",5);evidence.put("validationDays",days);evidence.put("windows",evidenceWindows);evidence.put("rows",rows);evidence.put("autoApplied",false);evidence.put("activationAllowed",false);evidence.put("limitations",List.of("验证时段不重叠，但同数据的训练时段可能重复；不自动证明独立样本外验证","固定成交序列成本重估，不重跑信号、止盈或资金约束；基础回撤来自原K线回测，非压力回撤","各窗口重置资金，不叠加收益率或推算组合收益","至少每窗口5笔且两种成本均正收益仅为研究筛选规则，不能证明策略有效或授权交易"));
        var result=new LinkedHashMap<String,Object>(evidence);result.put("evidenceHash",DatasetRegistry.hash(JsonUtils.toJsonByte(evidence)));return result;
    }
    private static Map<String,Map<String,Object>> index(List<Map<String,Object>> rows){if(rows==null)throw new IllegalArgumentException("缺少候选证据");var result=new TreeMap<String,Map<String,Object>>();for(var row:rows){String id=String.valueOf(row.get("strategyVersionId"));if(row.get("strategyVersionId")==null||result.put(id,row)!=null)throw new IllegalArgumentException("候选重复或缺失");}return result;}
    private static BigDecimal number(Map<String,Object> row,String key){if(!(row.get(key) instanceof Number value)||!Double.isFinite(value.doubleValue()))throw new IllegalArgumentException("缺少有效数值证据");return new BigDecimal(value.toString());}
    private static int integer(Map<String,Object> row,String key){try{return number(row,key).intValueExact();}catch(ArithmeticException e){throw new IllegalArgumentException("成交数和成本须为整数");}}
}
