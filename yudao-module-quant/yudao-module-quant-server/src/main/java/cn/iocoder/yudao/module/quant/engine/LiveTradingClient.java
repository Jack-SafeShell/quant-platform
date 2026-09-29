package cn.iocoder.yudao.module.quant.engine;

public interface LiveTradingClient {
    boolean configured();
    String accountBalance();
    String pendingOrders();
    String placeSpotLimitOrder(String clientOrderId, String side, String price, String amount);
    String getOrder(String clientOrderId);
    String cancelOrder(String clientOrderId);
}
