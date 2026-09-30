package cn.iocoder.yudao.module.quant.engine;

public interface LiveTradingClient {
    boolean configured();
    String accountBalance();
    String pendingOrders();
    default String marketCandles(){throw new UnsupportedOperationException("行情接口未实现");}
    default String marketTicker(){throw new UnsupportedOperationException("实时行情接口未实现");}
    String placeSpotLimitOrder(String clientOrderId, String side, String price, String amount);
    String getOrder(String clientOrderId);
    String cancelOrder(String clientOrderId);
}
