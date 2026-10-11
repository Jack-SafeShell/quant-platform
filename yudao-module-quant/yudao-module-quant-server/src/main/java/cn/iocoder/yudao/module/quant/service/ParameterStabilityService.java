package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.*;
import cn.iocoder.yudao.module.quant.dal.*;
import cn.iocoder.yudao.module.quant.engine.DatasetRegistry;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Three fixed neighbours in one history; never ranks, applies or admits a strategy. */
@Service
public class ParameterStabilityService {
    private final ParameterStabilityRepository repo;
    private final BacktestRepository tasks;
    private final BacktestService backtests;
    private final HistoricalSegmentsService history;
    private final TransactionTemplate transaction;
    public ParameterStabilityService(ParameterStabilityRepository repo,BacktestRepository tasks,BacktestService backtests,HistoricalSegmentsService history,PlatformTransactionManager manager){
        this.repo=repo;this.tasks=tasks;this.backtests=backtests;this.history=history;transaction=new TransactionTemplate(manager);
    }
    private static int number(Map<String,Object> c,String key){return ((Number)c.get(key)).intValue();}
    private static BigDecimal decimal(Map<String,Object> c,String key){return new BigDecimal(c.get(key).toString());}
    private static EmaStrategyRequest ema(Map<String,Object> c){return new EmaStrategyRequest(number(c,"fastPeriod"),number(c,"slowPeriod"),decimal(c,"stopLossRatio"),decimal(c,"takeProfitRatio"));}
    private static BreakoutStrategyRequest breakout(Map<String,Object> c){return new BreakoutStrategyRequest(number(c,"entryPeriod"),number(c,"exitPeriod"),decimal(c,"stopLossRatio"),decimal(c,"takeProfitRatio"));}
    private static RsiStrategyRequest rsi(Map<String,Object> c){return new RsiStrategyRequest(number(c,"rsiPeriod"),number(c,"entryThreshold"),number(c,"exitThreshold"),decimal(c,"stopLossRatio"),decimal(c,"takeProfitRatio"));}
    private static BollingerRsiStrategyRequest bollinger(Map<String,Object> c){return new BollingerRsiStrategyRequest(number(c,"bandPeriod"),decimal(c,"bandDeviation"),number(c,"rsiPeriod"),number(c,"rsiCeiling"),decimal(c,"stopLossRatio"),decimal(c,"takeProfitRatio"));}
    private static EmaAdxStrategyRequest emaAdx(Map<String,Object> c){return new EmaAdxStrategyRequest(number(c,"fastPeriod"),number(c,"slowPeriod"),number(c,"adxPeriod"),number(c,"adxThreshold"),decimal(c,"stopLossRatio"),decimal(c,"takeProfitRatio"));}
    private static BreakoutAtrStrategyRequest breakoutAtr(Map<String,Object> c){return new BreakoutAtrStrategyRequest(number(c,"entryPeriod"),number(c,"exitPeriod"),number(c,"atrPeriod"),decimal(c,"atrMultiplier"),decimal(c,"stopLossRatio"),decimal(c,"takeProfitRatio"));}
    private static String render(Map<String,Object> c)throws Exception{
        return switch(Objects.toString(c.get("template"),"EMA")){
            case "BREAKOUT_ATR" -> BreakoutAtrStrategyTemplate.render(breakoutAtr(c));
            case "EMA_ADX" -> EmaAdxStrategyTemplate.render(emaAdx(c));
            case "BOLLINGER_RSI" -> BollingerRsiStrategyTemplate.render(bollinger(c));
            case "RSI_REVERSION" -> RsiStrategyTemplate.render(rsi(c));
            case "CHANNEL_BREAKOUT" -> BreakoutStrategyTemplate.render(breakout(c));
            case "EMA" -> EmaStrategyTemplate.render(ema(c));
            default -> throw new IllegalArgumentException("当前策略不是受控历史模板");
        };
    }
    public static List<Map<String,Object>> neighbours(String source,String parameter,int step)throws Exception{
        var base=StrategyTemplates.readConfiguration(source);
        if(base==null || step<1 || step>10) throw new IllegalArgumentException("请选择受控历史策略及1～10的调整幅度");
        var keys=switch(Objects.toString(base.get("template"),"EMA")){
            case "BREAKOUT_ATR" -> List.of("entryPeriod","exitPeriod","atrPeriod");
            case "EMA_ADX" -> List.of("fastPeriod","slowPeriod","adxPeriod","adxThreshold");
            case "BOLLINGER_RSI" -> List.of("bandPeriod","rsiPeriod","rsiCeiling");
            case "RSI_REVERSION" -> List.of("rsiPeriod","entryThreshold","exitThreshold");
            case "CHANNEL_BREAKOUT" -> List.of("entryPeriod","exitPeriod");
            default -> List.of("fastPeriod","slowPeriod");
        };
        if(!keys.contains(parameter)) throw new IllegalArgumentException("所选参数不属于这个策略");
        int original=number(base,parameter);var variants=new ArrayList<Map<String,Object>>();
        for(int offset=-1;offset<=1;offset++){
            var c=new TreeMap<String,Object>(base);c.put(parameter,original+offset*step);
            String rendered=offset==0?source:render(c);
            var variant=new TreeMap<String,Object>();variant.put("role",offset<0?"LOWER":offset>0?"UPPER":"BASE");variant.put("value",original+offset*step);
            variant.put("configuration",c);variant.put("strategyHash",DatasetRegistry.hash(rendered.getBytes(StandardCharsets.UTF_8)));variants.add(variant);
        }
        return variants;
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> preview(long tenant,long owner,ParameterStabilityRequest r)throws Exception{
        // Reuse full history/hash/ownership checks; two seven-day windows imply a 14-day minimum.
        var plan=new TreeMap<>(history.preview(tenant,owner,new HistoricalSegmentsRequest(r.requestKey(),r.strategyVersionId(),r.parameterSetId(),r.datasetId(),r.startDate(),r.endDate(),2)));
        var version=tasks.findVersion(tenant,owner,r.strategyVersionId());
        var variants=neighbours((String)version.get("sourceCode"),r.parameter(),r.step());
        if(!plan.get("strategyHash").equals(variants.get(1).get("strategyHash"))) throw new IllegalArgumentException("原策略在预览期间变化");
        plan.remove("windows");plan.remove("segmentCount");plan.remove("splitMode");plan.put("schemaVersion","quant-parameter-stability/v1");
        plan.put("parameter",r.parameter());plan.put("step",r.step());plan.put("variants",variants);plan.put("minTrades",5);
        return plan;
    }
    @SuppressWarnings("unchecked")
    public String create(long tenant,long owner,ParameterStabilityRequest r)throws Exception{
        String hash=DatasetRegistry.hash(JsonUtils.toJsonByte(r));var previous=repo.byKey(tenant,owner,r.requestKey());
        if(previous!=null)return reuse(previous,hash);
        var plan=preview(tenant,owner,r);var params=JsonUtils.getObjectMapper().readTree((String)plan.get("parametersJson"));
        try {
            return transaction.execute(status->{
                if(tasks.pending(tenant,owner)+3>10)throw new IllegalArgumentException("本次检查会超过10个待处理任务上限，请等待已有任务完成");
                String id=UUID.randomUUID().toString();var ids=new ArrayList<String>();
                for(var v:(List<Map<String,Object>>)plan.get("variants")){
                    try{
                        var c=(Map<String,Object>)v.get("configuration");String version;
                        if("BASE".equals(v.get("role")))version=r.strategyVersionId();
                        else version=switch(Objects.toString(c.get("template"),"EMA")){
                            case "BREAKOUT_ATR" -> backtests.createBreakoutAtrVersion(tenant,owner,breakoutAtr(c));
                            case "EMA_ADX" -> backtests.createEmaAdxVersion(tenant,owner,emaAdx(c));
                            case "BOLLINGER_RSI" -> backtests.createBollingerRsiVersion(tenant,owner,bollinger(c));
                            case "RSI_REVERSION" -> backtests.createRsiVersion(tenant,owner,rsi(c));
                            case "CHANNEL_BREAKOUT" -> backtests.createBreakoutVersion(tenant,owner,breakout(c));
                            default -> backtests.createStrategyVersion(tenant,owner,ema(c));
                        };
                        v.put("strategyVersionId",version);
                        String task=backtests.create(tenant,owner,new BacktestRequest("stability-"+id+"-"+v.get("role"),version,r.parameterSetId(),r.datasetId(),r.startDate(),r.endDate(),params.path("startingBalance").decimalValue(),params.path("stakeAmount").decimalValue(),params.path("fee").decimalValue(),(String)plan.get("pair"),(String)plan.get("timeframe")));
                        if(!bound(tasks.find(tenant,owner,task),plan,v))throw new IllegalArgumentException("策略或行情在创建期间变化，请重新预览");ids.add(task);
                    }catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalArgumentException("创建检查失败，请核对历史行情",e);}
                }
                repo.insert(id,tenant,owner,r.requestKey(),hash,JsonUtils.toJsonString(plan),JsonUtils.toJsonString(ids));return id;
            });
        }catch(DuplicateKeyException e){previous=repo.byKey(tenant,owner,r.requestKey());if(previous==null)throw e;return reuse(previous,hash);}
    }
    private static String reuse(Map<String,Object> previous,String hash){
        if(!hash.equals(previous.get("requestHash")))throw new IllegalArgumentException("同一请求标识已用于不同检查条件");return (String)previous.get("id");
    }
    private static boolean bound(Map<String,Object> task,Map<String,Object> plan,Map<String,Object> variant){
        var binding=new TreeMap<>(plan);binding.put("strategyVersionId",variant.get("strategyVersionId"));binding.put("strategyHash",variant.get("strategyHash"));
        return HistoricalSegmentsService.bound(task,binding,plan);
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> plan(Map<String,Object> record){return JsonUtils.parseObject((String)record.get("planJson"),Map.class);}
    public List<Map<String,Object>> list(long tenant,long owner){
        return repo.list(tenant,owner).stream().map(record->{var p=plan(record);p.put("id",record.get("id"));p.put("createdAt",record.get("createdAt"));return p;}).toList();
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> get(long tenant,long owner,String id){
        var record=repo.get(tenant,owner,id);if(record==null)throw new IllegalArgumentException("稳定性检查不存在或无权访问");
        var plan=plan(record);var variants=(List<Map<String,Object>>)plan.get("variants");var ids=JsonUtils.parseObject((String)record.get("taskIdsJson"),List.class);
        if(ids.size()!=3 || variants.size()!=3)throw new IllegalArgumentException("检查任务证据不完整");
        var rows=new ArrayList<Map<String,Object>>();boolean terminal=true,complete=true;
        for(int i=0;i<3;i++){
            var v=variants.get(i);var task=tasks.find(tenant,owner,(String)ids.get(i));var row=new TreeMap<String,Object>(v);row.put("taskId",ids.get(i));
            String state=bound(task,plan,v)?(String)task.get("status"):"EVIDENCE_MISMATCH";boolean available="SUCCEEDED".equals(state);
            terminal &= !Set.of("QUEUED","RUNNING").contains(state);
            if(available){
                tools.jackson.databind.JsonNode m;
                try{m=JsonUtils.getObjectMapper().readTree((String)task.get("resultJson"));}catch(RuntimeException e){m=null;}
                final var checked=m;
                if(m==null || !List.of("returnRatio","netProfit","maxDrawdownRatio","totalTrades").stream().allMatch(k->checked.path(k).isNumber())
                        || !m.path("totalTrades").isIntegralNumber() || !m.path("totalTrades").canConvertToInt() || m.path("totalTrades").asInt()<0
                        || m.path("maxDrawdownRatio").decimalValue().signum()<0 || task.get("artifactHash")==null){available=false;state="EVIDENCE_MISMATCH";}
                else{
                    for(String key:List.of("returnRatio","netProfit","maxDrawdownRatio"))row.put(key,m.path(key).decimalValue());
                    row.put("totalTrades",m.path("totalTrades").asInt());row.put("noFills",m.path("totalTrades").asInt()==0);row.put("artifactHash",task.get("artifactHash"));
                }
            }
            complete &= available;row.put("status",state);row.put("available",available);rows.add(row);
        }
        var result=new TreeMap<String,Object>();result.put("id",id);result.put("createdAt",record.get("createdAt"));result.put("plan",plan);result.put("rows",rows);
        result.put("terminal",terminal);result.put("status",complete?"SUCCEEDED":terminal?"INCOMPLETE":"RUNNING");
        if(complete){
            BigDecimal base=(BigDecimal)rows.get(1).get("returnRatio");for(var row:rows)row.put("returnDelta",((BigDecimal)row.get("returnRatio")).subtract(base));
            result.put("summary",summary(rows));
        }
        result.put("limitations",List.of(("BREAKOUT_ATR".equals(((Map<?,?>)variants.get(1).get("configuration")).get("template"))?"只改变一个周期，ATR倍数与最大止损保持相同；":"EMA_ADX".equals(((Map<?,?>)variants.get(1).get("configuration")).get("template"))?"只改变一个周期或ADX强度刻度；":"只改变一个周期或RSI阈值；")+"止损、止盈、资金、费用及行情日期保持相同，每组重置本金。", "固定下调/原值/上调三组，不排序、不自动选择参数或启动交易。", "每组至少5笔成交只用于标记样本是否不足，不代表统计显著或稳定盈利；单一历史窗口不足以证明未来表现。", "收益差按本金收益率计算，回撤沿用原历史引擎口径；未重跑压力成本，不代表持仓低点回撤。"));
        result.put("autoApplied",false);result.put("executionAllowed",false);result.put("evidenceHashVersion","SORTED_JSON_V1");result.put("evidenceHash",HistoricalEvidence.hash(result));return result;
    }
    public static Map<String,Object> summary(List<Map<String,Object>> rows){
        var returns=rows.stream().map(row->(BigDecimal)row.get("returnRatio")).toList();int minTrades=rows.stream().mapToInt(row->((Number)row.get("totalTrades")).intValue()).min().orElseThrow();
        String finding=minTrades<5?"INSUFFICIENT_TRADES":returns.stream().allMatch(v->v.signum()>0)?"ALL_POSITIVE_THIS_WINDOW":returns.stream().allMatch(v->v.signum()<=0)?"ALL_NON_POSITIVE":"DIRECTION_CHANGED";
        return Map.of("finding",finding,"minTrades",minTrades,"worstReturn",Collections.min(returns),"bestReturn",Collections.max(returns),
                "returnSpread",Collections.max(returns).subtract(Collections.min(returns)),"maxDrawdown",rows.stream().map(row->(BigDecimal)row.get("maxDrawdownRatio")).max(BigDecimal::compareTo).orElseThrow(),
                "noFillVariants",rows.stream().filter(row->Boolean.TRUE.equals(row.get("noFills"))).count());
    }
}
