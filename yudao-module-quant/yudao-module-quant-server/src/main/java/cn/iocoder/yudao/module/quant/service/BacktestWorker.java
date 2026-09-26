package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.BacktestRequest;
import cn.iocoder.yudao.module.quant.dal.BacktestRepository;
import cn.iocoder.yudao.module.quant.engine.*;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

/** Single application instance. Durable queue; no automatic rerun of interrupted work. */
@Slf4j
@Component
public class BacktestWorker {
    private final BacktestRepository repository;
    private final BacktestEngine engine;
    private final DatasetRegistry datasets;
    private final QuantProperties properties;
    private final TransactionTemplate transaction;
    private ScheduledExecutorService executor;
    private FileChannel lockChannel;
    private FileLock workspaceLock;
    private volatile String activeId;
    public BacktestWorker(BacktestRepository repository, BacktestEngine engine, DatasetRegistry datasets, QuantProperties properties, PlatformTransactionManager manager) {
        this.repository = repository; this.engine = engine; this.datasets = datasets; this.properties = properties; this.transaction = new TransactionTemplate(manager);
    }
    @EventListener(ApplicationReadyEvent.class)
    public void start() throws Exception {
        if (!properties.isEnabled()) return;
        Path root = Path.of(properties.getWorkspace()).toAbsolutePath();
        Files.createDirectories(root);
        lockChannel = FileChannel.open(root.resolve("worker.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        workspaceLock = lockChannel.tryLock();
        if (workspaceLock == null) throw new IllegalStateException("量化工作目录已被另一个单体进程占用");
        for (String id : repository.runningIds()) {
            engine.stop(id);
            repository.fail(id, "应用重启中断；未自动重试，请使用新请求标识重新提交");
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "quant-backtest"); t.setDaemon(true); return t; });
        executor.scheduleWithFixedDelay(this::tick, 0, 2, TimeUnit.SECONDS);
    }
    public void tick() {
        try {
            Map<String, Object> task = repository.next();
            if (task == null) return;
            String id = (String) task.get("id");
            if (!repository.claim(id)) return;
            activeId = id;
            try {
                BacktestRequest params = JsonUtils.parseObject((String) task.get("parametersJson"), BacktestRequest.class);
                DatasetRegistry.Dataset dataset = datasets.load(params.datasetId(), LocalDate.parse(params.startDate()), LocalDate.parse(params.endDate()));
                if (!dataset.sha256().equals(task.get("datasetHash")) || !properties.getImage().equals(task.get("engineImage")))
                    throw new IllegalArgumentException("排队期间数据或引擎版本改变，拒绝执行");
                BacktestEngine.Output output = engine.run(new BacktestEngine.Input(id, params, dataset, (String) task.get("strategySource"), (String) task.get("strategyHash")));
                transaction.executeWithoutResult(status -> repository.complete(id, output));
            } catch (Exception e) {
                // Exception text may contain paths or remote content. Never expose it to API clients.
                log.warn("Backtest {} failed; local diagnostic follows", id, e);
                repository.fail(id, "回测执行或结果校验失败；请检查本机任务日志和数据覆盖，使用新请求标识重试");
            } finally { activeId = null; }
        } catch (Exception e) { log.error("Backtest queue unavailable ({})", e.getClass().getSimpleName()); }
    }
    @PreDestroy
    public void close() throws Exception {
        if (executor != null) { executor.shutdown(); if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
            String id = activeId; if (id != null) engine.stop(id); executor.shutdownNow(); executor.awaitTermination(25, TimeUnit.SECONDS);
        } }
        if (workspaceLock != null) workspaceLock.close();
        if (lockChannel != null) lockChannel.close();
    }
}
