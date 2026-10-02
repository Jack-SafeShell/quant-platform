package cn.iocoder.yudao.module.quant.service;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PaperStartupObservationTest {
    private boolean pending(String status,long age,boolean database,boolean engine,int network,int fatal){
        return PaperObservationMonitorService.startupPending(
                Map.of("status",status,"updated_at",100_000L),
                Map.of("engineRunningSeen",engine,"networkErrorCount",network,"fatalErrorCount",fatal),
                Map.of("databasePresent",database),100_000L+age);
    }
    @Test void missingDatabaseIsPendingOnlyDuringBoundedInitialization(){
        assertTrue(pending("STARTING",0,false,false,0,0));
        assertTrue(pending("RUNNING",59_999,false,false,0,0));
        assertFalse(pending("RUNNING",60_000,false,false,0,0));
        assertFalse(pending("RUNNING",-1,false,false,0,0));
        assertFalse(pending("STOPPED",1,false,false,0,0));
    }
    @Test void initializedOrFailedEngineNeverGetsStartupGrace(){
        assertFalse(pending("RUNNING",1,true,false,0,0));
        assertFalse(pending("RUNNING",1,false,true,0,0));
        assertFalse(pending("RUNNING",1,false,false,1,0));
        assertFalse(pending("RUNNING",1,false,false,0,1));
    }
}
