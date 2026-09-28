package cn.iocoder.yudao.module.quant.engine;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Component
public class OkxPrivateApiClient {
    private final QuantProperties properties;private final LiveCredentialProvider credentials;private final HttpClient http;
    @org.springframework.beans.factory.annotation.Autowired
    public OkxPrivateApiClient(QuantProperties properties,LiveCredentialProvider credentials){this(properties,credentials,HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build());}
    OkxPrivateApiClient(QuantProperties properties,LiveCredentialProvider credentials,HttpClient http){this.properties=properties;this.credentials=credentials;this.http=http;}
    public boolean configured(){return credentials.configured();}
    public String accountBalance() { return request("GET","/api/v5/account/balance?ccy=BTC,USDT",""); }
    public String placeSpotLimitOrder(String clientOrderId,String side,String price,String amount){
        if(!properties.isLiveExecutionEnabled())throw new IllegalStateException("真实执行总开关关闭");
        Map<String,Object> body=new LinkedHashMap<>();body.put("instId",properties.getLivePair().replace('/','-'));body.put("tdMode","cash");body.put("clOrdId",clientOrderId);body.put("side",side.toLowerCase(Locale.ROOT));body.put("ordType","limit");body.put("px",price);body.put("sz",amount);
        return request("POST","/api/v5/trade/order",JsonUtils.toJsonString(body));
    }
    private String request(String method,String path,String body){
        var value=credentials.load().orElseThrow(()->new IllegalStateException("OKX 加密凭据未配置"));String timestamp=Instant.now().toString();
        try{
            var builder=HttpRequest.newBuilder(URI.create(properties.getLiveOkxBaseUrl()+path)).timeout(java.time.Duration.ofSeconds(15)).header("Accept","application/json").header("Content-Type","application/json").header("OK-ACCESS-KEY",value.apiKey()).header("OK-ACCESS-SIGN",sign(timestamp,method,path,body,value.secretKey())).header("OK-ACCESS-TIMESTAMP",timestamp).header("OK-ACCESS-PASSPHRASE",value.passphrase());
            HttpRequest request="GET".equals(method)?builder.GET().build():builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()/100!=2)throw new IllegalStateException("OKX 私有接口 HTTP "+response.statusCode());return response.body();
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("OKX 私有接口请求被中断");}catch(Exception e){if(e instanceof IllegalStateException state)throw state;throw new IllegalStateException("OKX 私有接口请求失败");}
    }
    public static String sign(String timestamp,String method,String path,String body,String secret){
        try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return Base64.getEncoder().encodeToString(mac.doFinal((timestamp+method+path+body).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("OKX 请求签名失败");}
    }
}
