package cn.iocoder.yudao.module.quant.api.backtest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record DatasetDownloadRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String requestKey,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String datasetId,
        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String startDate,
        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String endDate,
        @Pattern(regexp="okx|binance") String exchange,
        @Pattern(regexp="BTC/USDT|ETH/USDT") String pair,
        @Pattern(regexp="1h|4h") String timeframe) {
    public DatasetDownloadRequest(String key,String id,String start,String end,String exchange){this(key,id,start,end,exchange,null,null);}
    public DatasetDownloadRequest(String key,String id,String start,String end){this(key,id,start,end,"okx");}
    public String exchangeName(){return exchange==null?"okx":exchange;}
    public String pairName(){return pair==null?"BTC/USDT":pair;}
    public String candleTimeframe(){return timeframe==null?"1h":timeframe;}
}
