package cn.iocoder.yudao.module.quant.engine;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class OkxPrivateApiClient implements LiveTradingClient {
    private static final DateTimeFormatter OKX_TIMESTAMP=DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private final QuantProperties properties;private final LiveCredentialProvider credentials;private final HttpClient http;
    @org.springframework.beans.factory.annotation.Autowired
    public OkxPrivateApiClient(QuantProperties properties,LiveCredentialProvider credentials){this(properties,credentials,buildHttpClient(properties));}
    OkxPrivateApiClient(QuantProperties properties,LiveCredentialProvider credentials,HttpClient http){this.properties=properties;this.credentials=credentials;this.http=http;}
    public boolean configured(){return credentials.configured();}
    public String accountBalance() { return privateRead("/api/v5/account/balance?ccy=BTC,USDT"); }
    public String pendingOrders(){return privateRead("/api/v5/trade/orders-pending?instType=SPOT&instId="+instrument());}
    public String marketCandles(){return publicRequest("/api/v5/market/candles?instId="+instrument()+"&bar=1H&limit=300");}
    public String marketTicker(){return publicRequest("/api/v5/market/ticker?instId="+instrument());}
    public String placeSpotLimitOrder(String clientOrderId,String side,String price,String amount){
        if(!properties.isLiveExecutionEnabled())throw new IllegalStateException("真实执行总开关关闭");
        Map<String,Object> body=new LinkedHashMap<>();body.put("instId",properties.getLivePair().replace('/','-'));body.put("tdMode","cash");body.put("clOrdId",clientOrderId);body.put("side",side.toLowerCase(Locale.ROOT));body.put("ordType","limit");body.put("px",price);body.put("sz",amount);
        return request("POST","/api/v5/trade/order",JsonUtils.toJsonString(body));
    }
    public String getOrder(String clientOrderId){return privateRead("/api/v5/trade/order?instId="+instrument()+"&clOrdId="+clientOrderId);}
    public String cancelOrder(String clientOrderId){
        if(!properties.isLiveExecutionEnabled())throw new IllegalStateException("真实执行总开关关闭");
        return request("POST","/api/v5/trade/cancel-order",JsonUtils.toJsonString(Map.of("instId",instrument(),"clOrdId",clientOrderId)));
    }
    private String instrument(){return properties.getLivePair().replace('/','-');}
    private String privateRead(String path){
        IOException failure=null;int attempts=properties.getLivePrivateReadMaxAttempts();
        for(int attempt=1;attempt<=attempts;attempt++)try{return sendPrivate("GET",path,"");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("OKX 私有接口请求被中断");}
        catch(IOException e){failure=e;if(attempt<attempts)pauseBeforeRetry();}
        throw transportFailure(failure,attempts);
    }
    private String publicRequest(String path){try{var request=HttpRequest.newBuilder(URI.create(properties.getLiveOkxBaseUrl()+path)).timeout(java.time.Duration.ofSeconds(15)).header("Accept","application/json").header("User-Agent","quant-platform/1.0").GET().build();var response=http.send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()/100!=2)throw new IllegalStateException("OKX 公开接口 HTTP "+response.statusCode());return response.body();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("OKX 公开接口请求被中断");}catch(Exception e){if(e instanceof IllegalStateException state)throw state;throw new IllegalStateException("OKX 公开接口请求失败");}}
    private String request(String method,String path,String body){
        try{return sendPrivate(method,path,body);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("OKX 私有接口请求被中断");}
        catch(IOException e){throw transportFailure(e,1);}
    }
    private String sendPrivate(String method,String path,String body) throws IOException,InterruptedException {
        var value=credentials.load().orElseThrow(()->new IllegalStateException("OKX 加密凭据未配置"));String timestamp=OKX_TIMESTAMP.format(Instant.now());
        var builder=HttpRequest.newBuilder(URI.create(properties.getLiveOkxBaseUrl()+path)).timeout(java.time.Duration.ofSeconds(15)).header("Accept","application/json").header("Content-Type","application/json").header("User-Agent","quant-platform/1.0").header("OK-ACCESS-KEY",value.apiKey()).header("OK-ACCESS-SIGN",sign(timestamp,method,path,body,value.secretKey())).header("OK-ACCESS-TIMESTAMP",timestamp).header("OK-ACCESS-PASSPHRASE",value.passphrase());
        HttpRequest request="GET".equals(method)?builder.GET().build():builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()/100!=2)throw new IllegalStateException("OKX 私有接口 HTTP "+response.statusCode());return response.body();
    }
    private void pauseBeforeRetry(){
        try{Thread.sleep(properties.getLivePrivateReadRetryDelayMillis());}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("OKX 私有接口请求被中断");}
    }
    private static IllegalStateException transportFailure(IOException failure,int attempts){
        Throwable root=failure;while(root.getCause()!=null)root=root.getCause();
        return new IllegalStateException("OKX 私有接口请求失败 ("+root.getClass().getSimpleName()+"，已尝试 "+attempts+" 次)",failure);
    }
    public static String sign(String timestamp,String method,String path,String body,String secret){
        try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return Base64.getEncoder().encodeToString(mac.doFinal((timestamp+method+path+body).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("OKX 请求签名失败");}
    }
    private static HttpClient buildHttpClient(QuantProperties properties){
        var builder=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10));String proxy=properties.getExchangeProxy();
        if(proxy!=null&&!proxy.isBlank()){URI uri=URI.create(proxy);if(uri.getHost()==null||uri.getPort()<1)throw new IllegalArgumentException("交易所代理地址无效");builder.proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(),uri.getPort())));}
        return builder.build();
    }
}
