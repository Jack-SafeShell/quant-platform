package cn.iocoder.yudao.module.quant.engine;

import java.util.Set;

/** Allowlisted historical spot contexts, independent of execution market settings. */
public record HistoricalMarket(String pair, String timeframe) {
    public HistoricalMarket {
        if (!Set.of("BTC/USDT", "ETH/USDT").contains(pair) || !Set.of("1h", "4h").contains(timeframe))
            throw new IllegalArgumentException("历史行情仅支持 BTC/USDT、ETH/USDT 现货及 1h、4h");
    }
    public long intervalMillis() { return "4h".equals(timeframe) ? 14400000L : 3600000L; }
    public String filename() { return pair.replace('/', '_') + "-" + timeframe + ".json"; }
    public boolean executionCompatible() { return "BTC/USDT".equals(pair) && "1h".equals(timeframe); }
}
