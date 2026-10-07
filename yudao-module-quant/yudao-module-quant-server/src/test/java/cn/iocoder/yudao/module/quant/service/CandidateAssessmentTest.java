package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.module.quant.api.backtest.CandidateAssessmentRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CandidateAssessmentTest {
    static CandidateAssessmentRequest request(){return new CandidateAssessmentRequest(List.of("a","b"),10,5,20,10);}
    static Map<String,Object> row(String version){var r=new HashMap<String,Object>();r.put("strategyVersionId",version);r.put("configuration",Map.of("fastPeriod",20));r.put("trainStatus","SUCCEEDED");r.put("validationStatus","SUCCEEDED");r.put("validationDrawdown",0.03);r.put("validationTrades",6);return r;}
    static Map<String,Object> costRow(String version,double value){var r=new HashMap<String,Object>();r.put("strategyVersionId",version);r.put("available",true);r.put("adjustedReturn",value);r.put("tradeCount",6);r.put("taskId",version);r.put("artifactHash","artifact-"+version);return r;}
    static CandidateAssessmentService.Window window(String id,String from,String to,double base,double stress){
        var e=new HashMap<String,Object>();e.put("id",id);e.put("datasetId","binance");e.put("parameterSetId","capital");e.put("parametersJson","{\"startingBalance\":1000}");e.put("terminal",true);e.put("splitDate",from);e.put("validationEnd",to);e.put("rows",new ArrayList<>(List.of(row("v1"),row("v2"))));
        var b=new HashMap<String,Object>();b.put("experimentId",id);b.put("datasetId","binance");b.put("validationStart",from);b.put("validationEnd",to);b.put("model","FIXED_TRADE_CASHFLOW_V1");b.put("feeBps",10);b.put("slippageBps",5);b.put("evidenceHash","base-"+id);b.put("rows",new ArrayList<>(List.of(costRow("v1",base),costRow("v2",base/2))));
        var s=new HashMap<>(b);s.put("feeBps",20);s.put("slippageBps",10);s.put("evidenceHash","stress-"+id);s.put("rows",new ArrayList<>(List.of(costRow("v1",stress),costRow("v2",stress/2))));return new CandidateAssessmentService.Window(e,b,s);
    }
    static List<CandidateAssessmentService.Window> windows(){return List.of(window("a","2026-05-01","2026-09-01",0.04,0.02),window("b","2026-09-01","2026-10-01",0.01,0.005));}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> rows(Map<String,Object> value){return (List<Map<String,Object>>)value.get("rows");}
    @SuppressWarnings("unchecked") static Map<String,Object> metric(Map<String,Object> value,int i){return ((List<Map<String,Object>>)value.get("rows")).get(i);}
    @Test void reportsWorstWindowAndNormalizedFrequencyWithoutCompoundingOrActivation(){
        var r=CandidateAssessmentService.evaluate(windows(),request());var row=rows(r).getFirst();assertEquals("RESEARCH_CANDIDATE",row.get("classification"));assertEquals("0.01",row.get("worstBaseReturn").toString());assertEquals("0.005",row.get("worstStressReturn").toString());assertEquals("2.3529",row.get("tradesPer30Days").toString());assertEquals(153L,r.get("validationDays"));assertFalse((Boolean)r.get("autoApplied"));assertFalse((Boolean)r.get("activationAllowed"));assertFalse(r.containsKey("portfolioReturn"));
    }
    @Test void reorderingInputPreservesEvidenceAndDoesNotMutateExperiments(){
        var input=windows();var before=new HashMap<>(input.getFirst().experiment());var result=CandidateAssessmentService.evaluate(input,request());var reversed=CandidateAssessmentService.evaluate(List.of(input.getLast(),input.getFirst()),new CandidateAssessmentRequest(List.of("b","a"),10,5,20,10));assertEquals(result.get("evidenceHash"),reversed.get("evidenceHash"));assertEquals(before,input.getFirst().experiment());
    }
    @Test void distinguishesCostSensitivityNegativeWindowsAndLowTradeSamples(){
        var a=windows().getFirst();var b=window("b","2026-09-01","2026-10-01",0.01,-0.001);assertEquals("COST_SENSITIVE",rows(CandidateAssessmentService.evaluate(List.of(a,b),request())).getFirst().get("classification"));
        b=window("b","2026-09-01","2026-10-01",0,0);assertEquals("INCONSISTENT",rows(CandidateAssessmentService.evaluate(List.of(a,b),request())).getFirst().get("classification"));
        metric(b.base(),0).put("tradeCount",0);metric(b.stress(),0).put("tradeCount",0);metric(b.experiment(),0).put("validationTrades",0);assertTrue(rows(CandidateAssessmentService.evaluate(List.of(a,b),request())).stream().anyMatch(r->"INSUFFICIENT_TRADES".equals(r.get("classification"))));
    }
    @Test void rejectsOverlapAndInvalidDateWindows(){for(String start:List.of("2026-08-31","2026-10-01")){var b=window("b",start,"2026-10-01",0.01,0.005);assertThrows(IllegalArgumentException.class,()->CandidateAssessmentService.evaluate(List.of(windows().getFirst(),b),request()));}}
    @Test void rejectsMixedDatasetCapitalVersionsAndIncompleteTasks(){
        for(String field:List.of("datasetId","parameterSetId","parametersJson","terminal")){var w=windows();w.getLast().experiment().put(field,field.equals("terminal")?false:"different");assertThrows(IllegalArgumentException.class,()->CandidateAssessmentService.evaluate(w,request()));}
        var changed=windows();metric(changed.getLast().experiment(),0).put("strategyVersionId","another");assertThrows(IllegalArgumentException.class,()->CandidateAssessmentService.evaluate(changed,request()));
        var failed=windows();metric(failed.getLast().experiment(),0).put("trainStatus","FAILED");assertThrows(IllegalArgumentException.class,()->CandidateAssessmentService.evaluate(failed,request()));
    }
    @Test void rejectsCostSourceArtifactCountAndNonfiniteEvidence(){
        for(String field:List.of("experimentId","validationStart","model")){var w=windows();w.getLast().base().put(field,"different");assertThrows(IllegalArgumentException.class,()->CandidateAssessmentService.evaluate(w,request()));}
        for(String field:List.of("artifactHash","taskId","available","tradeCount","adjustedReturn")){var w=windows();metric(w.getLast().stress(),0).put(field,switch(field){case "available"->false;case "tradeCount"->7;case "adjustedReturn"->Double.NaN;default->"different";});assertThrows(IllegalArgumentException.class,()->CandidateAssessmentService.evaluate(w,request()));}
    }
    @Test void rejectsDuplicateExperimentAndWeakenedStressAssumptions(){assertThrows(IllegalArgumentException.class,()->CandidateAssessmentService.validate(new CandidateAssessmentRequest(List.of("a","a"),10,5,20,10)));assertThrows(IllegalArgumentException.class,()->CandidateAssessmentService.validate(new CandidateAssessmentRequest(List.of("a","b"),10,5,9,10)));assertThrows(IllegalArgumentException.class,()->CandidateAssessmentService.validate(new CandidateAssessmentRequest(List.of("a","b"),10,5,20,4)));}
    @Test void scopesEveryExperimentAndCostReadToCaller(){
        var source=windows();var calls=new ArrayList<String>();
        var experiments=new StrategyExperimentService(null,null,null,new DataSourceTransactionManager()){
            @Override public Map<String,Object> get(long tenant,long owner,String id){if(tenant!=7||owner!=8)throw new IllegalArgumentException("not owned");calls.add("get:"+id);return source.stream().filter(w->id.equals(w.experiment().get("id"))).findFirst().orElseThrow().experiment();}
            @Override public Map<String,Object> costs(long tenant,long owner,String id,int fee,int slip){if(tenant!=7||owner!=8)throw new IllegalArgumentException("not owned");calls.add("cost:"+id);var w=source.stream().filter(v->id.equals(v.experiment().get("id"))).findFirst().orElseThrow();return fee==10?w.base():w.stress();}
        };
        var service=new CandidateAssessmentService(experiments);assertThrows(IllegalArgumentException.class,()->service.assess(7,9,request()));assertTrue(calls.isEmpty());assertNotNull(service.assess(7,8,request()).get("evidenceHash"));assertEquals(6,calls.size());
    }
}
