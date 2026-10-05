package cn.iocoder.yudao.module.quant.engine;

import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Deployment selects one account; a market selector never switches private credentials. */
@Component @Primary
public class ConfiguredLiveTradingClient implements LiveTradingClient {
    private final QuantProperties properties;
    private final OkxPrivateApiClient okx;
    private final BinancePrivateApiClient binance;
    public ConfiguredLiveTradingClient(QuantProperties properties,OkxPrivateApiClient okx,BinancePrivateApiClient binance){this.properties=properties;this.okx=okx;this.binance=binance;}
    private LiveTradingClient selected(){return switch(properties.getLiveExchange()){case "okx"->okx;case "binance"->binance;default->throw new IllegalStateException("Unsupported private exchange");};}
    public boolean configured(){return selected().configured();}
    public String accountBalance(){return selected().accountBalance();}
    public String pendingOrders(){return selected().pendingOrders();}
    public String marketCandles(){return selected().marketCandles();}
    public String marketTicker(){return selected().marketTicker();}
    public void validateSpotLimitOrder(String side,String price,String amount){selected().validateSpotLimitOrder(side,price,amount);}
    public void preflightSpotLimitOrder(String side,String price,String amount){selected().preflightSpotLimitOrder(side,price,amount);}
    public java.math.BigDecimal limitPrice(String side,java.math.BigDecimal price){return selected().limitPrice(side,price);}
    public java.math.BigDecimal limitAmount(java.math.BigDecimal amount){return selected().limitAmount(amount);}
    public String placeSpotLimitOrder(String id,String side,String price,String amount){return selected().placeSpotLimitOrder(id,side,price,amount);}
    public String getOrder(String id){return selected().getOrder(id);}
    public String cancelOrder(String id){return selected().cancelOrder(id);}
}
