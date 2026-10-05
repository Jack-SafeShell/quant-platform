package cn.iocoder.yudao.module.quant.engine;

public interface LiveTradingClient {
    boolean configured();
    String accountBalance();
    String pendingOrders();
    default void validateSpotLimitOrder(String side,String price,String amount) {}
    default void preflightSpotLimitOrder(String side,String price,String amount){throw new UnsupportedOperationException("Exchange preflight unavailable");}
    default java.math.BigDecimal limitPrice(String side,java.math.BigDecimal price){return price;}
    default java.math.BigDecimal limitAmount(java.math.BigDecimal amount){return amount;}
    default String marketCandles(){throw new UnsupportedOperationException("行情接口未实现");}
    default String marketTicker(){throw new UnsupportedOperationException("实时行情接口未实现");}
    String placeSpotLimitOrder(String clientOrderId, String side, String price, String amount);
    String getOrder(String clientOrderId);
    String cancelOrder(String clientOrderId);
}
