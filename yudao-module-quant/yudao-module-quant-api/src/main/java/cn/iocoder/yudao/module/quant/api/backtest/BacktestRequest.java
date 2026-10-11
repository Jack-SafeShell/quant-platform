package cn.iocoder.yudao.module.quant.api.backtest;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/** Historical spot market is resolved from its verified dataset. Dates are UTC; end is exclusive. */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record BacktestRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String requestKey,
        @NotBlank @Pattern(regexp = "[0-9a-f-]{36}") String strategyVersionId,
        @NotBlank @Pattern(regexp = "[0-9a-f-]{36}") String parameterSetId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String datasetId,
        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String startDate,
        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String endDate,
        @NotNull @DecimalMin("100") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2) BigDecimal startingBalance,
        @NotNull @DecimalMin("10") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2) BigDecimal stakeAmount,
        @NotNull @DecimalMin("0") @DecimalMax("0.01") @Digits(integer = 1, fraction = 6) BigDecimal fee,
        @Pattern(regexp="BTC/USDT|ETH/USDT") String pair,
        @Pattern(regexp="1h|4h") String timeframe) {
    public BacktestRequest(String key,String strategy,String parameter,String dataset,String start,String end,BigDecimal balance,BigDecimal stake,BigDecimal fee) {
        this(key,strategy,parameter,dataset,start,end,balance,stake,fee,null,null);
    }
    public BacktestRequest withMarket(String pair,String timeframe) {
        return new BacktestRequest(requestKey,strategyVersionId,parameterSetId,datasetId,startDate,endDate,startingBalance,stakeAmount,fee,pair,timeframe);
    }
    public String pairName(){return pair==null?"BTC/USDT":pair;}
    public String candleTimeframe(){return timeframe==null?"1h":timeframe;}
}
