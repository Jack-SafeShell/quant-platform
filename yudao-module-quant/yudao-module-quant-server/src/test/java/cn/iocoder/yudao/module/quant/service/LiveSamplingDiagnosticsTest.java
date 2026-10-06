package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.module.quant.dal.*;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class LiveSamplingDiagnosticsTest {
    static Map<String,Object> latest(Map<String,Object> view){return (Map<String,Object>)view.get("latest");}
    @Test void reportsInFlightStageAndUsesMonotonicTimeDespiteWallClockJump(){
        var wall=new AtomicLong(1000);var nanos=new AtomicLong();var d=new LiveSamplingDiagnostics(wall::get,nanos::get);
        var sample=d.begin(1,2,"a");nanos.set(10_000_000L);sample.stage(LiveSamplingDiagnostics.Stage.ACCOUNT);
        wall.set(-100000);nanos.set(110_000_000_000L);var view=d.get(1,2,"a");var current=latest(view);
        assertEquals("RUNNING",current.get("outcome"));assertEquals("ACCOUNT",current.get("stage"));assertEquals(110000L,current.get("elapsedMillis"));
        assertEquals(Map.of("BINDING",10L,"ACCOUNT",109990L),current.get("stageMillis"));assertFalse(view.containsKey("lastCompleted"));
        sample.finish(false);assertEquals("COMPLETED",latest(d.get(1,2,"a")).get("outcome"));nanos.addAndGet(99_000_000L);assertEquals(110000L,latest(d.get(1,2,"a")).get("elapsedMillis"));
    }
    @Test void failureRetainsPhaseWithoutStoringFailurePayloadAndFinishIsIdempotent(){
        var nanos=new AtomicLong();var d=new LiveSamplingDiagnostics(()->1000,nanos::get);var sample=d.begin(1,2,"private-session");
        sample.stage(LiveSamplingDiagnostics.Stage.OPEN_ORDERS);nanos.set(15_000_000_000L);sample.finish(true);sample.finish(false);
        var current=latest(d.get(1,2,"private-session"));assertEquals("FAILED",current.get("outcome"));assertEquals(15000L,current.get("elapsedMillis"));assertFalse(current.toString().contains("private-session"));
    }
    @Test void diagnosticsAreScopedToTenantOwnerAndProcess(){
        var d=new LiveSamplingDiagnostics();d.begin(1,2,"a");assertEquals(false,d.get(2,2,"a").get("available"));assertEquals(false,d.get(1,3,"a").get("available"));
        assertEquals(false,new LiveSamplingDiagnostics().get(1,2,"a").get("available"));assertEquals("CURRENT_PROCESS",d.get(1,2,"a").get("scope"));assertEquals(false,d.get(1,2,"a").get("persisted"));
    }
    @Test void lateCompletionDoesNotOverwriteNewerSample(){
        var d=new LiveSamplingDiagnostics();var first=d.begin(1,2,"a");first.finish(false);var second=d.begin(1,2,"a");var third=d.begin(1,2,"a");third.finish(false);second.finish(true);
        var view=d.get(1,2,"a");assertEquals(3L,latest(view).get("sampleSequence"));assertEquals(3L,((Map<?,?>)view.get("lastCompleted")).get("sampleSequence"));
    }
    @Test void historyIsBoundedWithoutReplacingOtherSessions(){
        var d=new LiveSamplingDiagnostics();for(int i=0;i<65;i++)d.begin(1,2,"s"+i).finish(false);
        assertEquals(false,d.get(1,2,"s0").get("available"));assertEquals(true,d.get(1,2,"s1").get("available"));assertEquals(true,d.get(1,2,"s64").get("available"));
    }
    @Test void serviceRecordsFailureAndExposesItOnlyAfterOwnedSessionLookup(){
        var p=new QuantProperties();
        var repo=new LiveAutomationRepository(null){
            public Map<String,Object> get(long t,long o,String id){return t==1&&o==2&&id.equals("s")?new HashMap<>(Map.of("id","s","policyId","p","tenantId",1L,"ownerId",2L,"status","RUNNING")):null;}
            public List<Map<String,Object>> signals(long t,long o,String id){return List.of();}
            public List<Map<String,Object>> snapshots(long t,long o,String id){return List.of();}
            public List<Map<String,Object>> alerts(long t,long o,String id){return List.of();}
        };
        var policies=new LiveControlRepository(null){
            public Map<String,Object> get(long t,long o,String id){return Map.of("accountId",p.getLiveAccountId(),"exchangeName",p.getLiveExchange());}
            public List<Map<String,Object>> audits(long t,long o,String id){return List.of();}
        };
        var controls=new LiveControlService(policies,null,p,null){
            public void requireAccount(long t,long o,String id){throw new IllegalStateException("sensitive-request-data");}
            public Map<String,Object> strategy(long t,long o,String id){return Map.of();}
        };
        var service=new LiveAutomationService(repo,policies,controls,null,null,p);
        assertThrows(IllegalStateException.class,()->service.tick("s",1,2));
        var diagnostics=(Map<String,Object>)service.get(1,2,"s").get("samplingDiagnostics");assertEquals("FAILED",latest(diagnostics).get("outcome"));assertEquals("BINDING",latest(diagnostics).get("stage"));assertFalse(diagnostics.toString().contains("sensitive-request-data"));
        assertThrows(IllegalArgumentException.class,()->service.get(9,2,"s"));
    }
}
