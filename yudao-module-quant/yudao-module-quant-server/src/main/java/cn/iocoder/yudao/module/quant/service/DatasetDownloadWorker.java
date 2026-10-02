package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.module.quant.dal.DatasetDownloadRepository;
import cn.iocoder.yudao.module.quant.engine.DatasetRegistry;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j @Component
public class DatasetDownloadWorker {
    private final DatasetDownloadRepository repository; private final DatasetRegistry datasets; private final QuantProperties properties; private final TransactionTemplate tx;
    private ScheduledExecutorService executor; private volatile Process active;
    public DatasetDownloadWorker(DatasetDownloadRepository repository,DatasetRegistry datasets,QuantProperties properties,PlatformTransactionManager manager){this.repository=repository;this.datasets=datasets;this.properties=properties;this.tx=new TransactionTemplate(manager);}
    @EventListener(ApplicationReadyEvent.class) public void start(){if(!properties.isEnabled())return;repository.runningIds().forEach(id->repository.fail(id,"应用重启中断；未自动重试，请使用新请求标识重新提交"));executor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"quant-dataset-download");t.setDaemon(true);return t;});executor.scheduleWithFixedDelay(this::tick,1,3,TimeUnit.SECONDS);}
    public void tick(){Map<String,Object> task=repository.next();if(task==null||!repository.claim((String)task.get("id")))return;String id=(String)task.get("id");try{
        ProcessBuilder builder=new ProcessBuilder(properties.getPythonExecutable(),properties.getDatasetScript(),"--id",(String)task.get("dataset_id"),"--start",(String)task.get("start_date"),"--end",(String)task.get("end_date"),"--workspace",Path.of(properties.getWorkspace()).toAbsolutePath().toString());
        if(properties.getDatasetHttpProxy()!=null&&!properties.getDatasetHttpProxy().isBlank()){builder.command().add("--proxy");builder.command().add(properties.getDatasetHttpProxy());}
        builder.environment().put("PYTHONIOENCODING","utf-8");
        builder.redirectErrorStream(true);active=builder.start();boolean finished=active.waitFor(properties.getTimeoutSeconds(),TimeUnit.SECONDS);if(!finished){active.destroyForcibly();throw new IllegalStateException("timeout");}byte[] output=active.getInputStream().readNBytes(8192);if(active.exitValue()!=0)throw new IllegalStateException(new String(output,StandardCharsets.UTF_8));
        var dataset=datasets.load((String)task.get("dataset_id"),LocalDate.parse((String)task.get("start_date")),LocalDate.parse((String)task.get("end_date")));
        tx.executeWithoutResult(s->repository.complete(id,dataset.sha256(),dataset.candles()));
      }catch(Exception e){log.warn("Dataset download {} failed; local diagnostic follows",id,e);repository.fail(id,"公开行情下载或完整性校验失败；请检查本机任务日志后使用新编号重试");}finally{active=null;}}
    @PreDestroy public void close()throws Exception{if(executor!=null){executor.shutdownNow();Process process=active;if(process!=null)process.destroyForcibly();executor.awaitTermination(5,TimeUnit.SECONDS);}}
}
