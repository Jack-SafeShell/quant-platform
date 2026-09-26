package cn.iocoder.yudao.module.quant.engine;

import cn.iocoder.yudao.module.quant.api.backtest.BacktestRequest;

/** Engine-neutral boundary: no trading operation is exposed. */
public interface BacktestEngine {
    record Input(String taskId, BacktestRequest parameters, DatasetRegistry.Dataset dataset,
                 String strategySource, String strategyHash) { }
    record Output(String engineVersion, String resultJson, String artifactHash) { }
    Output run(Input input) throws Exception;
    void stop(String taskId) throws Exception;
}
