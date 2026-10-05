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
    private final QuantProperties properties;private final LiveCredentialProvider credentials;private final HttpClient http;private final cn.iocoder.yudao.module.quant.dal.ExchangeAccountRepository accounts;private volatile VerifiedAccount verifiedAccount;
    private record VerifiedAccount(String keyHash,String uid) {}
    @org.springframework.beans.factory.annotation.Autowired
    public OkxPrivateApiClient(QuantProperties properties,LiveCredentialProvider credentials,cn.iocoder.yudao.module.quant.dal.ExchangeAccountRepository accounts){this(properties,credentials,buildHttpClient(properties),accounts);}
    OkxPrivateApiClient(QuantProperties properties,LiveCredentialProvider credentials,HttpClient http){this(properties,credentials,http,null);}
    OkxPrivateApiClient(QuantProperties properties,LiveCredentialProvider credentials,HttpClient http,cn.iocoder.yudao.module.quant.dal.ExchangeAccountRepository accounts){this.properties=properties;this.credentials=credentials;this.http=http;this.accounts=accounts;}
    public boolean configured(){return "okx".equals(properties.getLiveExchange())&&credentials.configured();}
    public String accountBalance() { return privateRead("/api/v5/account/balance?ccy=BTC,USDT"); }
    public String pendingOrders(){return privateRead("/api/v5/trade/orders-pending?instType=SPOT&instId="+instrument());}
    public String marketCandles(){return publicRequest("/api/v5/market/candles?instId="+instrument()+"&bar=1H&limit=300");}
    public String marketTicker(){return publicRequest("/api/v5/market/ticker?instId="+instrument());}
    public void preflightSpotLimitOrder(String side,String price,String amount){
        if(!Set.of("BUY","SELL").contains(side)||!"okx".equals(properties.getLiveExchange())||!"BTC/USDT".equals(properties.getLivePair()))throw new IllegalArgumentException("Invalid OKX spot context");
        var instruments=JsonUtils.getObjectMapper().readTree(publicRequest("/api/v5/public/instruments?instType=SPOT&instId="+instrument()));
        var config=JsonUtils.getObjectMapper().readTree(privateRead("/api/v5/account/config"));
        validatePreflight(instruments,config,new java.math.BigDecimal(price),new java.math.BigDecimal(amount));
    }
    public static void validatePreflight(tools.jackson.databind.JsonNode instruments,tools.jackson.databind.JsonNode config,java.math.BigDecimal price,java.math.BigDecimal amount){
        if(!"0".equals(instruments.path("code").asText())||!instruments.path("data").isArray()||instruments.path("data").size()!=1)throw new IllegalArgumentException("OKX instruments missing");
        var row=instruments.path("data").get(0);
        if(!"BTC-USDT".equals(row.path("instId").asText())||!"SPOT".equals(row.path("instType").asText())||!"live".equals(row.path("state").asText()))throw new IllegalArgumentException("OKX spot instrument unavailable");
        var tick=new java.math.BigDecimal(row.path("tickSz").asText());var lot=new java.math.BigDecimal(row.path("lotSz").asText());var min=new java.math.BigDecimal(row.path("minSz").asText());
        if(tick.signum()<=0||lot.signum()<=0||min.signum()<=0||price.signum()<=0||amount.compareTo(min)<0||price.remainder(tick).signum()!=0||amount.remainder(lot).signum()!=0)throw new IllegalArgumentException("OKX price or quantity violates instrument rules");
        if(!"0".equals(config.path("code").asText())||!config.path("data").isArray()||config.path("data").size()!=1)throw new IllegalArgumentException("OKX permission evidence missing");
        var permissions=new HashSet<>(Arrays.asList(config.path("data").get(0).path("perm").asText().split(",")));
        if(!permissions.equals(Set.of("read_only","trade")))throw new IllegalArgumentException("OKX key needs read and trade without withdrawal permission");
    }
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
        if(!"okx".equals(properties.getLiveExchange()))throw new IllegalStateException("OKX client cannot access another exchange account");
        if(!credentials.configured())throw new IllegalStateException("Encrypted credential is not configured");
        if(accounts!=null)accounts.requireCredentialReference();
        var value=credentials.load().orElseThrow(()->new IllegalStateException("OKX 加密凭据未配置"));String keyHash=DatasetRegistry.hash(value.apiKey().getBytes(StandardCharsets.UTF_8));
        if(accounts!=null){
            var verified=verifiedAccount;
            if(verified==null||!keyHash.equals(verified.keyHash()))synchronized(this){
                verified=verifiedAccount;
                if(verified==null||!keyHash.equals(verified.keyHash())){
                    var identity=JsonUtils.getObjectMapper().readTree(sendSigned("GET","/api/v5/account/config","",value));
                    if(!"0".equals(identity.path("code").asText())||identity.path("data").isEmpty())throw new IllegalStateException("Exchange identity verification failed");
                    String uid=identity.path("data").get(0).path("uid").asText();
                    if(!uid.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalStateException("Exchange account identity missing");
                    verified=new VerifiedAccount(keyHash,uid);verifiedAccount=verified;
                }
            }
            accounts.bindIdentity(verified.uid());
        }
        return sendSigned(method,path,body,value);
    }
    private String sendSigned(String method,String path,String body,LiveCredentialProvider.OkxCredential value) throws IOException,InterruptedException {
        String timestamp=OKX_TIMESTAMP.format(Instant.now());
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
