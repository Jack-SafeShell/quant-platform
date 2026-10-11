package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.engine.DatasetRegistry;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Read-only reconstruction from the owned task and its original, hash-verified candles. */
@Service
public class BacktestReviewService {
    private final BacktestService backtests;
    private final DatasetRegistry datasets;
    public BacktestReviewService(BacktestService backtests, DatasetRegistry datasets) {
        this.backtests = backtests; this.datasets = datasets;
    }
    public Map<String, Object> get(long tenant, long owner, String id) {
        // Authorization precedes all file access and compatibility fallbacks.
        var task = backtests.get(tenant, owner, id);
        if (!"SUCCEEDED".equals(task.get("status"))) return unavailable("TASK_NOT_COMPLETED");
        try {
            var request = JsonUtils.getObjectMapper().readTree((String) task.get("parametersJson"));
            var start = LocalDate.parse(request.path("startDate").asText());
            var end = LocalDate.parse(request.path("endDate").asText());
            var dataset = datasets.load((String) task.get("datasetId"), start, end);
            if (!dataset.sha256().equals(task.get("datasetHash")) || !dataset.exchange().equals(task.get("exchangeName")) || !dataset.pair().equals(request.path("pair").asText("BTC/USDT"))
                    || !dataset.timeframe().equals(request.path("timeframe").asText("1h"))) return unavailable("DATASET_CHANGED");
            long from = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            long to = end.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            return HistoricalReview.calculate((String) task.get("resultJson"), request.path("startingBalance").asDouble(),
                    request.path("fee").asDouble(), from, to, datasets.readCandles(dataset, from, to), dataset.market());
        } catch (Exception e) { return unavailable("HISTORICAL_EVIDENCE_UNAVAILABLE"); }
    }
    static Map<String, Object> unavailable(String reason) { return Map.of("available", false, "reason", reason); }
}
