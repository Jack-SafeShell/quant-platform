package cn.iocoder.yudao.module.quant.service;

import java.util.*;
import java.util.function.LongSupplier;

/** Bounded, process-local timings. Never stores request data or exception messages. */
final class LiveSamplingDiagnostics {
    enum Stage { BINDING, ORDER_RECONCILIATION, ACCOUNT, INVENTORY, OPEN_ORDERS, CANDLES, VALUATION, SNAPSHOT_WRITE, SIGNAL_EXECUTION }
    private record Key(long tenant,long owner,String session) {}
    private static final int MAX_SESSIONS=64;
    private final LinkedHashMap<Key,Entry> entries=new LinkedHashMap<>();
    private final LongSupplier wall,nano;
    private long sequence;
    private static final class Entry { Sample current,completed; }
    LiveSamplingDiagnostics(){this(System::currentTimeMillis,System::nanoTime);}
    LiveSamplingDiagnostics(LongSupplier wall,LongSupplier nano){this.wall=wall;this.nano=nano;}
    synchronized Sample begin(long tenant,long owner,String session){
        var key=new Key(tenant,owner,session);
        var entry=entries.computeIfAbsent(key,k->new Entry());
        while(entries.size()>MAX_SESSIONS)entries.remove(entries.keySet().iterator().next());
        var sample=new Sample(entry,++sequence);entry.current=sample;return sample;
    }
    synchronized Map<String,Object> get(long tenant,long owner,String session){
        var result=new LinkedHashMap<String,Object>();result.put("scope","CURRENT_PROCESS");result.put("persisted",false);
        var entry=entries.get(new Key(tenant,owner,session));result.put("available",entry!=null);
        if(entry!=null){result.put("latest",entry.current.snapshot());if(entry.completed!=null)result.put("lastCompleted",entry.completed.snapshot());}
        return result;
    }
    final class Sample {
        private final Entry entry;
        private final long number,startedAt=wall.getAsLong(),start=nano.getAsLong();
        private final Map<Stage,Long> stages=new EnumMap<>(Stage.class);
        private Stage stage=Stage.BINDING;
        private long stageStart=start,end,finishedAt;
        private String outcome="RUNNING";
        Sample(Entry entry,long number){this.entry=entry;this.number=number;}
        void stage(Stage next){synchronized(LiveSamplingDiagnostics.this){
            if(!outcome.equals("RUNNING"))return;
            long now=nano.getAsLong();record(now);stage=next;stageStart=now;
        }}
        void finish(boolean failed){synchronized(LiveSamplingDiagnostics.this){
            if(!outcome.equals("RUNNING"))return;
            end=nano.getAsLong();record(end);finishedAt=wall.getAsLong();outcome=failed?"FAILED":"COMPLETED";
            if(entry.completed==null||entry.completed.number<number)entry.completed=this;
        }}
        private void record(long now){stages.merge(stage,Math.max(0,now-stageStart),Long::sum);}
        private Map<String,Object> snapshot(){
            long now=outcome.equals("RUNNING")?nano.getAsLong():end;
            var durations=new TreeMap<String,Long>();stages.forEach((key,value)->durations.put(key.name(),value/1_000_000));
            if(outcome.equals("RUNNING"))durations.merge(stage.name(),Math.max(0,now-stageStart)/1_000_000,Long::sum);
            var result=new LinkedHashMap<String,Object>();result.put("sampleSequence",number);result.put("startedAt",startedAt);result.put("outcome",outcome);result.put("stage",stage.name());
            result.put("elapsedMillis",Math.max(0,now-start)/1_000_000);result.put("stageMillis",durations);
            if(!outcome.equals("RUNNING"))result.put("finishedAt",finishedAt);
            return result;
        }
    }
}
