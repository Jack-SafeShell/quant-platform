package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.math.BigDecimal;
import java.util.*;

/** Public data only: independent of the configured trading account and its credentials. */
@Service
public class PublicMarketService {
    private final HttpClient http;
    public PublicMarketService(QuantProperties properties){
        var builder=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        if(!properties.getDatasetHttpProxy().isBlank()){
            URI proxy=URI.create(properties.getDatasetHttpProxy());
            if(!Set.of("http","https").contains(proxy.getScheme())||proxy.getHost()==null||proxy.getPort()<1||proxy.getUserInfo()!=null)throw new IllegalArgumentException("Invalid public market proxy");
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(),proxy.getPort())));
        }
        http=builder.build();
    }
    public Map<String,Object> snapshot(String exchange){
        if(!Set.of("okx","binance").contains(exchange))throw new IllegalArgumentException("Unsupported market exchange");
        boolean binance="binance".equals(exchange);String base=binance?"https://data-api.binance.vision":"https://www.okx.com";
        var clock=get(base+(binance?"/api/v3/time":"/api/v5/public/time"));
        long serverTime=binance?clock.path("serverTime").asLong():okxData(clock).get(0).path("ts").asLong();
        var ticker=get(base+(binance?"/api/v3/ticker/bookTicker?symbol=BTCUSDT":"/api/v5/market/ticker?instId=BTC-USDT"));
        var candles=get(base+(binance?"/api/v3/klines?symbol=BTCUSDT&interval=1h&limit=200":"/api/v5/market/candles?instId=BTC-USDT&bar=1H&limit=200"));
        return normalize(exchange,serverTime,ticker,candles);
    }
    public static Map<String,Object> normalize(String exchange,long serverTime,JsonNode ticker,JsonNode raw){
        if(serverTime<=0||!Set.of("okx","binance").contains(exchange))throw new IllegalArgumentException("Invalid exchange clock");
        boolean binance="binance".equals(exchange);var book=binance?ticker:okxData(ticker).get(0);
        BigDecimal bid=positive(book.path(binance?"bidPrice":"bidPx")),ask=positive(book.path(binance?"askPrice":"askPx"));
        if(bid.compareTo(ask)>0)throw new IllegalArgumentException("Invalid market spread");
        var rows=binance?raw:okxData(raw);if(!rows.isArray())throw new IllegalArgumentException("Candle response missing");
        var closed=new TreeMap<Long,List<Object>>();
        for(var row:rows){
            if(!row.isArray()||row.size()<(binance?7:9))throw new IllegalArgumentException("Incomplete candle");
            long at=row.get(0).asLong(-1),closeTime=at+3600000-1;
            if(at<0||at%3600000!=0||(binance&&row.get(6).asLong(-1)!=closeTime))throw new IllegalArgumentException("Invalid candle time");
            if(closeTime>=serverTime||(!binance&&!"1".equals(row.get(8).asText())))continue;
            BigDecimal open=positive(row.get(1)),high=positive(row.get(2)),low=positive(row.get(3)),close=positive(row.get(4)),volume=new BigDecimal(row.get(5).asText());
            if(volume.signum()<0||high.compareTo(open.max(close).max(low))<0||low.compareTo(open.min(close).min(high))>0)throw new IllegalArgumentException("Invalid OHLCV");
            if(closed.putIfAbsent(at,List.of(at,open,high,low,close,volume))!=null)throw new IllegalArgumentException("Duplicate candle");
        }
        if(closed.isEmpty())throw new IllegalArgumentException("No closed candle");
        long previous=-1;for(long at:closed.keySet()){if(previous>=0&&at-previous!=3600000)throw new IllegalArgumentException("Candle gap");previous=at;}
        if(serverTime-(closed.lastKey()+3600000)>3600000)throw new IllegalArgumentException("Stale closed candles");
        return Map.of("exchange",exchange,"pair","BTC/USDT","timeframe","1h","bid",bid,"ask",ask,"exchangeTime",serverTime,"observedAt",System.currentTimeMillis(),"lastClosedCandleAt",closed.lastKey(),"closedCandles",new ArrayList<>(closed.values()),"publicOnly",true);
    }
    private static BigDecimal positive(JsonNode node){BigDecimal n=new BigDecimal(node.asText());if(n.signum()<=0)throw new IllegalArgumentException("Invalid public price");return n;}
    private static JsonNode okxData(JsonNode root){if(!"0".equals(root.path("code").asText())||!root.path("data").isArray()||root.path("data").isEmpty())throw new IllegalArgumentException("OKX market response rejected");return root.path("data");}
    private JsonNode get(String url){
        try{var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("Accept","application/json").GET().build();var response=http.send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()/100!=2)throw new IllegalArgumentException("Public market HTTP "+response.statusCode());return JsonUtils.getObjectMapper().readTree(response.body());}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Public market request interrupted");}
        catch(java.io.IOException e){throw new IllegalStateException("Public market request failed");}
    }
}
