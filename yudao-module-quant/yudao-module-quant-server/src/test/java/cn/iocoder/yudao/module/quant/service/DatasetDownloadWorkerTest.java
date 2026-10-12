package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.module.quant.dal.DatasetDownloadRepository;
import cn.iocoder.yudao.module.quant.engine.DatasetRegistry;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DatasetDownloadWorkerTest {
    @Test void schedulerKeepsPollingAfterQueueReadFailure() throws Exception {
        verifySchedulerRecovery(false);
    }

    @Test void schedulerKeepsPollingAfterClaimFailure() throws Exception {
        verifySchedulerRecovery(true);
    }

    private void verifySchedulerRecovery(boolean claimFailure) throws Exception {
        var repository=new RepositoryFake(false,claimFailure);
        var datasets=new DatasetFake();
        var properties=new QuantProperties();properties.setEnabled(true);
        var worker=new DatasetDownloadWorker(repository,datasets,properties,unusedTransactions());
        try{
            worker.start();
            assertTrue(repository.nextPoll.await(7,TimeUnit.SECONDS),"A transient queue failure must not cancel scheduled polling");
            assertTrue(repository.calls.get()>=2);
            assertEquals(0,repository.failures);
            assertEquals(0,datasets.loads);
            assertEquals(claimFailure?1:0,repository.claims);
        }finally{worker.close();}
    }

    @Test void invalidClaimedTaskStillFailsWithoutRetryingDownload() throws Exception {
        var repository=new RepositoryFake(true,false);
        var datasets=new DatasetFake();
        var worker=new DatasetDownloadWorker(repository,datasets,new QuantProperties(),unusedTransactions());
        assertDoesNotThrow(worker::tick);
        assertEquals(1,repository.failures);
        assertEquals("invalid-task",repository.failedId);
        assertEquals("公开行情下载或完整性校验失败；请检查本机任务日志后使用新编号重试",repository.message);
        assertDoesNotThrow(worker::tick);
        assertEquals(1,repository.claims);
        assertEquals(1,repository.failures);
        assertEquals(0,datasets.loads);
    }

    private static class RepositoryFake extends DatasetDownloadRepository {
        final boolean invalid,claimFailure;
        final AtomicInteger calls=new AtomicInteger();
        final CountDownLatch nextPoll=new CountDownLatch(1);
        int claims,failures;String failedId,message;
        RepositoryFake(boolean invalid,boolean claimFailure){super(null);this.invalid=invalid;this.claimFailure=claimFailure;}
        @Override public List<String> runningIds(){return List.of();}
        @Override public Map<String,Object> next(){
            if(calls.incrementAndGet()==1){
                if(invalid)return Map.of("id","invalid-task","pair_name","unsupported","timeframe","1h");
                if(claimFailure)return Map.of("id","queued-task");
                throw new CannotGetJdbcConnectionException("transient connection failure");
            }
            nextPoll.countDown();return null;
        }
        @Override public boolean claim(String id){claims++;if(claimFailure)throw new CannotGetJdbcConnectionException("transient claim failure");return true;}
        @Override public void fail(String id,String message){failures++;failedId=id;this.message=message;}
    }
    private static class DatasetFake extends DatasetRegistry {
        int loads;
        DatasetFake(){super(new QuantProperties());}
        @Override public Dataset load(String id,java.time.LocalDate start,java.time.LocalDate end){loads++;throw new AssertionError("No download or dataset access expected");}
    }
    private static PlatformTransactionManager unusedTransactions(){
        return new PlatformTransactionManager(){
            @Override public TransactionStatus getTransaction(TransactionDefinition definition){throw new AssertionError("No transaction expected");}
            @Override public void commit(TransactionStatus status){throw new AssertionError("No transaction expected");}
            @Override public void rollback(TransactionStatus status){throw new AssertionError("No transaction expected");}
        };
    }
}
