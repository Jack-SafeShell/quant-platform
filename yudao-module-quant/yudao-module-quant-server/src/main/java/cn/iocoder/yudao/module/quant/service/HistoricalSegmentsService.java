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
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Fixed strategy, independent capital per window; no fitting, admission or execution changes. */
@Service
public class HistoricalSegmentsService {
    private final HistoricalSegmentsRepository repo;
    private final BacktestRepository tasks;
    private final BacktestService backtests;
    private final DatasetRegistry datasets;
    private final TransactionTemplate transaction;
    public HistoricalSegmentsService(HistoricalSegmentsRepository repo,BacktestRepository tasks,BacktestService backtests,DatasetRegistry datasets,PlatformTransactionManager manager) {
        this.repo=repo;this.tasks=tasks;this.backtests=backtests;this.datasets=datasets;transaction=new TransactionTemplate(manager);
    }
    public static List<Map<String,Object>> windows(String startDate,String endDate,int count) {
        LocalDate start,end;
        try { start=LocalDate.parse(startDate);end=LocalDate.parse(endDate); }
        catch (DateTimeException | NullPointerException e) { throw new IllegalArgumentException("请提供有效的 UTC 日期"); }
        long days=ChronoUnit.DAYS.between(start,end);
        if(count<2 || count>5 || days<count*7L || days>366) throw new IllegalArgumentException("请选择2～5段，每段至少7天，总区间最多366天，结束日不含");
        List<Map<String,Object>> rows=new ArrayList<>();
        for(int i=0;i<count;i++) {
            LocalDate a=start.plusDays(days*i/count),b=start.plusDays(days*(i+1)/count);
            rows.add(Map.of("index",i+1,"startDate",a.toString(),"endDate",b.toString(),"days",ChronoUnit.DAYS.between(a,b)));
        }
        return rows;
    }
    public Map<String,Object> preview(long tenant,long owner,HistoricalSegmentsRequest r) throws Exception {
        var windows=windows(r.startDate(),r.endDate(),r.segmentCount());
        var version=tasks.findVersion(tenant,owner,r.strategyVersionId());
        var parameter=tasks.findParameterSet(tenant,owner,r.parameterSetId());
        if(version==null || parameter==null) throw new IllegalArgumentException("策略或资金参数不存在或无权访问");
        String source=(String)version.get("sourceCode");
        if(!DatasetRegistry.hash(source.getBytes(StandardCharsets.UTF_8)).equals(version.get("sourceHash"))) throw new IllegalArgumentException("策略版本摘要不符");
        String params=(String)parameter.get("parametersJson");
        if(!DatasetRegistry.hash(params.getBytes(StandardCharsets.UTF_8)).equals(parameter.get("parametersHash"))) throw new IllegalArgumentException("资金参数摘要不符");
        DatasetRegistry.Dataset dataset;
        try { dataset=datasets.load(r.datasetId(),LocalDate.parse(r.startDate()),LocalDate.parse(r.endDate())); }
        catch(java.nio.file.NoSuchFileException e) { throw new IllegalArgumentException("行情尚未准备，请先下载完整数据"); }
        var plan=new TreeMap<String,Object>();
        plan.put("schemaVersion","quant-historical-segments/v1");plan.put("splitMode","EQUAL_UTC_DAYS");
        plan.put("strategyVersionId",r.strategyVersionId());plan.put("strategyHash",version.get("sourceHash"));
        plan.put("configuration",StrategyTemplates.readConfiguration(source));
        plan.put("parameterSetId",r.parameterSetId());plan.put("parametersJson",params);
        plan.put("datasetId",r.datasetId());plan.put("datasetHash",dataset.sha256());plan.put("exchange",dataset.exchange());
        plan.put("pair",dataset.pair());plan.put("timeframe",dataset.timeframe());
        plan.put("startDate",r.startDate());plan.put("endDate",r.endDate());plan.put("segmentCount",r.segmentCount());plan.put("windows",windows);
        return plan;
    }
    public String create(long tenant,long owner,HistoricalSegmentsRequest r) throws Exception {
        String hash=DatasetRegistry.hash(JsonUtils.toJsonByte(r));
        var previous=repo.byKey(tenant,owner,r.requestKey());
        if(previous!=null) return reuse(previous,hash);
        var plan=preview(tenant,owner,r);
        var params=JsonUtils.getObjectMapper().readTree((String)plan.get("parametersJson"));
        var windows=windows(r.startDate(),r.endDate(),r.segmentCount());
        try {
            return transaction.execute(status -> {
                if(tasks.pending(tenant,owner)+r.segmentCount()>10) throw new IllegalArgumentException("本次分段会超过10个待处理任务上限，请等待已有任务完成");
                String id=UUID.randomUUID().toString();var ids=new ArrayList<String>();
                for(var window:windows) {
                    try {
                        String task=backtests.create(tenant,owner,new BacktestRequest("segments-"+id+"-"+window.get("index"),r.strategyVersionId(),r.parameterSetId(),r.datasetId(),(String)window.get("startDate"),(String)window.get("endDate"),params.path("startingBalance").decimalValue(),params.path("stakeAmount").decimalValue(),params.path("fee").decimalValue(),(String)plan.get("pair"),(String)plan.get("timeframe")));
                        if(!bound(tasks.find(tenant,owner,task),plan,window)) throw new IllegalArgumentException("行情或策略在创建期间变化，请重新预览");
                        ids.add(task);
                    } catch(RuntimeException e) { throw e; }
                    catch(Exception e) { throw new IllegalArgumentException("创建分段回测失败，请检查行情是否完整",e); }
                }
                repo.insert(id,tenant,owner,r.requestKey(),hash,JsonUtils.toJsonString(plan),JsonUtils.toJsonString(ids));
                return id;
            });
        } catch(DuplicateKeyException e) {
            previous=repo.byKey(tenant,owner,r.requestKey());if(previous==null) throw e;return reuse(previous,hash);
        }
    }
    private static String reuse(Map<String,Object> previous,String hash) {
        if(!hash.equals(previous.get("requestHash"))) throw new IllegalArgumentException("同一请求标识已用于不同分段条件");return (String)previous.get("id");
    }
    private static boolean bound(Map<String,Object> task,Map<String,Object> plan,Map<String,Object> window) {
        if(task==null || !(task.get("strategySource") instanceof String)) return false;
        var r=JsonUtils.getObjectMapper().readTree((String)task.get("parametersJson"));
        var p=JsonUtils.getObjectMapper().readTree((String)plan.get("parametersJson"));
        return Objects.equals(plan.get("datasetHash"),task.get("datasetHash")) && Objects.equals(plan.get("datasetId"),task.get("datasetId"))
                && Objects.equals(plan.get("exchange"),task.get("exchangeName")) && Objects.equals(plan.get("strategyHash"),task.get("strategyHash"))
                && Objects.equals(plan.get("strategyVersionId"),task.get("strategyVersionId"))
                && Objects.equals(plan.get("parameterSetId"),r.path("parameterSetId").asText())
                && Objects.equals(window.get("startDate"),r.path("startDate").asText()) && Objects.equals(window.get("endDate"),r.path("endDate").asText())
                && Objects.equals(plan.get("pair"),r.path("pair").asText()) && Objects.equals(plan.get("timeframe"),r.path("timeframe").asText())
                && List.of("startingBalance","stakeAmount","fee").stream().allMatch(k->p.path(k).isNumber() && r.path(k).isNumber() && p.path(k).decimalValue().compareTo(r.path(k).decimalValue())==0)
                && DatasetRegistry.hash(((String)task.get("strategySource")).getBytes(StandardCharsets.UTF_8)).equals(plan.get("strategyHash"));
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> plan(Map<String,Object> record) { return JsonUtils.parseObject((String)record.get("planJson"),Map.class); }
    public List<Map<String,Object>> list(long tenant,long owner) {
        return repo.list(tenant,owner).stream().map(record->{var p=plan(record);p.put("id",record.get("id"));p.put("createdAt",record.get("createdAt"));return p;}).toList();
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> get(long tenant,long owner,String id) {
        var record=repo.get(tenant,owner,id);if(record==null) throw new IllegalArgumentException("分段回测不存在或无权访问");
        var plan=plan(record);var windows=(List<Map<String,Object>>)plan.get("windows");
        var ids=JsonUtils.parseObject((String)record.get("taskIdsJson"),List.class);
        if(ids.size()!=windows.size()) throw new IllegalArgumentException("分段任务证据不完整");
        var rows=new ArrayList<Map<String,Object>>();boolean terminal=true,complete=true;
        for(int i=0;i<ids.size();i++) {
            var task=tasks.find(tenant,owner,(String)ids.get(i));var row=new LinkedHashMap<String,Object>(windows.get(i));row.put("taskId",ids.get(i));
            boolean matches=bound(task,plan,windows.get(i));String state=matches?(String)task.get("status"):"EVIDENCE_MISMATCH";
            row.put("status",state);boolean succeeded="SUCCEEDED".equals(state);terminal &= !Set.of("QUEUED","RUNNING").contains(state);
            if(succeeded) {
                tools.jackson.databind.JsonNode metrics;
                try { metrics=JsonUtils.getObjectMapper().readTree((String)task.get("resultJson")); }
                catch(RuntimeException e) { metrics=null; }
                final var checked=metrics;
                if(metrics==null || !List.of("returnRatio","netProfit","maxDrawdownRatio","totalTrades").stream().allMatch(k->checked.path(k).isNumber())
                        || !metrics.path("totalTrades").isIntegralNumber() || !metrics.path("totalTrades").canConvertToInt() || metrics.path("totalTrades").asInt()<0
                        || metrics.path("maxDrawdownRatio").decimalValue().signum()<0 || task.get("artifactHash")==null) {
                    succeeded=false;row.put("status","EVIDENCE_MISMATCH");
                } else {
                    for(String key:List.of("returnRatio","netProfit","maxDrawdownRatio")) row.put(key,metrics.path(key).decimalValue());
                    row.put("totalTrades",metrics.path("totalTrades").asInt());row.put("noFills",metrics.path("totalTrades").asInt()==0);row.put("artifactHash",task.get("artifactHash"));
                }
            }
            complete &= succeeded;row.put("available",succeeded);rows.add(row);
        }
        var result=new TreeMap<String,Object>();result.put("id",id);result.put("createdAt",record.get("createdAt"));result.put("plan",plan);result.put("rows",rows);
        result.put("terminal",terminal);result.put("status",complete?"SUCCEEDED":terminal?"INCOMPLETE":"RUNNING");
        if(complete) {
            var returns=rows.stream().map(row->(BigDecimal)row.get("returnRatio")).toList();
            result.put("summary",Map.of("positiveWindows",returns.stream().filter(v->v.signum()>0).count(),"windowCount",rows.size(),
                    "noFillWindows",rows.stream().filter(row->Boolean.TRUE.equals(row.get("noFills"))).count(),
                    "worstReturn",Collections.min(returns),"bestReturn",Collections.max(returns),
                    "maxDrawdown",rows.stream().map(row->(BigDecimal)row.get("maxDrawdownRatio")).max(BigDecimal::compareTo).orElseThrow(),
                    "minTrades",rows.stream().mapToInt(row->((Number)row.get("totalTrades")).intValue()).min().orElseThrow()));
        }
        result.put("limitations",List.of("各段使用同一固定策略，独立重置本金，不相加或复利合并收益。", "按UTC日期等天数切分，每段至少7天；各段结束日不含，段末持仓由历史引擎结束回测规则处理。", "预热240根K线，不计入各段交易；回撤使用原历史引擎口径。", "无成交不代表策略稳定；已用于研究的数据不是未见样本。结果不自动选策略、改参数或启动交易。"));
        result.put("autoApplied",false);result.put("executionAllowed",false);
        result.put("evidenceHash",DatasetRegistry.hash(JsonUtils.toJsonByte(result)));return result;
    }
}
