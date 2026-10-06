package cn.iocoder.yudao.module.quant.engine;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.dal.ExchangeAccountRepository;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import cn.iocoder.yudao.module.quant.service.PublicMarketService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;

/** Binance spot HMAC adapter. Normalized responses preserve the existing ledger contract. */
@lombok.extern.slf4j.Slf4j
@Component
public class BinancePrivateApiClient implements LiveTradingClient {
    private final QuantProperties properties;
    private final LiveCredentialProvider credentials;
    private final ExchangeAccountRepository accounts;
    private final HttpClient http;
    private final HttpClient publicHttp;
    private final LongSupplier nanoTime;
    private volatile ServerClock serverClock;
    private record ServerClock(long serverMillis,long receivedNanos) {}
    private static final long CLOCK_TTL_NANOS=Duration.ofSeconds(60).toNanos();
    private static final long MAX_CLOCK_ROUND_TRIP_NANOS=Duration.ofMillis(2500).toNanos();
    @org.springframework.beans.factory.annotation.Autowired
    public BinancePrivateApiClient(QuantProperties properties,LiveCredentialProvider credentials,ExchangeAccountRepository accounts){this(properties,credentials,accounts,http(properties.getExchangeProxy()),http(properties.getDatasetHttpProxy()));}
    BinancePrivateApiClient(QuantProperties properties,LiveCredentialProvider credentials,ExchangeAccountRepository accounts,HttpClient http){this(properties,credentials,accounts,http,http);}
    BinancePrivateApiClient(QuantProperties properties,LiveCredentialProvider credentials,ExchangeAccountRepository accounts,HttpClient http,LongSupplier nanoTime){this(properties,credentials,accounts,http,http,nanoTime);}
    private BinancePrivateApiClient(QuantProperties properties,LiveCredentialProvider credentials,ExchangeAccountRepository accounts,HttpClient http,HttpClient publicHttp){this(properties,credentials,accounts,http,publicHttp,System::nanoTime);}
    private BinancePrivateApiClient(QuantProperties properties,LiveCredentialProvider credentials,ExchangeAccountRepository accounts,HttpClient http,HttpClient publicHttp,LongSupplier nanoTime){this.properties=properties;this.credentials=credentials;this.accounts=accounts;this.http=http;this.publicHttp=publicHttp;this.nanoTime=nanoTime;}
    public boolean configured(){return "binance".equals(properties.getLiveExchange())&&credentials.configured();}
    public void preflightSpotLimitOrder(String side,String price,String amount){validateSpotLimitOrder(side,price,amount);}
    private void requireSelected(){if(!"binance".equals(properties.getLiveExchange())||!"BTC/USDT".equals(properties.getLivePair()))throw new IllegalStateException("Binance account/pair not selected");}
    private LiveCredentialProvider.OkxCredential credential(){requireSelected();if(!configured())throw new IllegalStateException("Binance encrypted credential not configured");if(accounts!=null)accounts.requireCredentialReference();return credentials.load().orElseThrow(()->new IllegalStateException("Binance encrypted credential not configured"));}
    // Verify the physical UID for every operation, including after a transaction rollback or key rotation.
    private JsonNode account(LiveCredentialProvider.OkxCredential credential){
        var account=signed("GET","/api/v3/account","",credential);
        String uid=account.path("uid").asText();
        if(!uid.matches("[0-9]{1,32}")||!"SPOT".equals(account.path("accountType").asText())||!account.path("balances").isArray())throw new IllegalStateException("Binance spot account identity missing");
        if(accounts!=null)accounts.bindIdentity(uid);return account;
    }
    private JsonNode privateGet(String path,String query){var key=credential();account(key);return signed("GET",path,query,key);}
    public String accountBalance(){
        var account=account(credential());var book=publicGet("/api/v3/ticker/bookTicker?symbol=BTCUSDT");BigDecimal mark=positive(book,"bidPrice");
        var details=new ArrayList<Map<String,Object>>();BigDecimal total=BigDecimal.ZERO;
        for(var balance:account.path("balances")){
            String asset=balance.path("asset").asText();if(!Set.of("BTC","USDT").contains(asset))continue;
            BigDecimal free=nonnegative(balance,"free"),locked=nonnegative(balance,"locked"),quantity=free.add(locked),value="BTC".equals(asset)?quantity.multiply(mark):quantity;
            total=total.add(value);details.add(Map.of("ccy",asset,"availBal",plain(free),"cashBal",plain(quantity),"eqUsd",plain(value)));
        }
        // This acceptance account is valued only in BTC/USDT; no invented BNB conversion.
        return envelope(List.of(Map.of("totalEq",plain(total),"details",details)));
    }
    public String pendingOrders(){
        var rows=privateGet("/api/v3/openOrders","symbol=BTCUSDT");if(!rows.isArray())throw new IllegalStateException("Binance open orders missing");
        var result=new ArrayList<Map<String,Object>>();for(var row:rows){requireSymbol(row);result.add(Map.of("clOrdId",required(row,"clientOrderId"),"ordId",required(row,"orderId")));}return envelope(result);
    }
    public String marketTicker(){requireSelected();var book=publicGet("/api/v3/ticker/bookTicker?symbol=BTCUSDT");var bid=positive(book,"bidPrice");var ask=positive(book,"askPrice");if(bid.compareTo(ask)>0)throw new IllegalStateException("Invalid Binance spread");return envelope(List.of(Map.of("bidPx",plain(bid),"askPx",plain(ask))));}
    public String marketCandles(){
        requireSelected();var clock=publicGet("/api/v3/time");var book=publicGet("/api/v3/ticker/bookTicker?symbol=BTCUSDT");var raw=publicGet("/api/v3/klines?symbol=BTCUSDT&interval=1h&limit=200");
        var snapshot=PublicMarketService.normalize("binance",clock.path("serverTime").asLong(),book,raw);
        var rows=new ArrayList<List<Object>>();for(Object value:(List<?>)snapshot.get("closedCandles")){var row=new ArrayList<Object>((List<?>)value);row.add("0");row.add("0");row.add("1");rows.add(row);}return envelope(rows);
    }
    public Map<String,Object> verifyReadOnly(){
        var key=credential();var account=account(key);var permissions=signed("GET","/sapi/v1/account/apiRestrictions","",key);
        var out=new LinkedHashMap<String,Object>();out.put("connected",true);out.put("ordersSent",0);out.put("canTrade",account.path("canTrade").asBoolean());
        for(String field:List.of("enableReading","enableSpotAndMarginTrading","enableWithdrawals","enableMargin","enableFutures","ipRestrict")){if(!permissions.path(field).isBoolean())throw new IllegalStateException("Binance key permissions incomplete");out.put(field,permissions.path(field).asBoolean());}return out;
    }
    public Map<String,Object> tradingRules(){return JsonUtils.parseObject(publicGet("/api/v3/exchangeInfo?symbol=BTCUSDT").toString(),Map.class);}
    public BigDecimal limitPrice(String side,BigDecimal price){return quantize(price,step("PRICE_FILTER","tickSize"),"SELL".equals(side)?RoundingMode.UP:RoundingMode.DOWN);}
    public BigDecimal limitAmount(BigDecimal amount){return quantize(amount,step("LOT_SIZE","stepSize"),RoundingMode.DOWN);}
    private BigDecimal step(String type,String field){requireSelected();var info=publicGet("/api/v3/exchangeInfo?symbol=BTCUSDT");if(info.path("symbols").size()!=1)throw new IllegalStateException("Binance filters missing");var symbol=info.path("symbols").get(0);requireSymbol(symbol);for(var filter:symbol.path("filters"))if(type.equals(filter.path("filterType").asText()))return positive(filter,field);throw new IllegalStateException("Binance precision filter missing");}
    static BigDecimal quantize(BigDecimal value,BigDecimal step,RoundingMode mode){if(value.signum()<0||step.signum()<=0)throw new IllegalArgumentException("Invalid Binance precision");return value.divide(step,0,mode).multiply(step);}
    public void validateSpotLimitOrder(String side,String price,String amount){
        requireSelected();if(!Set.of("BUY","SELL").contains(side.toUpperCase(Locale.ROOT)))throw new IllegalArgumentException("Invalid spot side");
        validateRules(publicGet("/api/v3/exchangeInfo?symbol=BTCUSDT"),new BigDecimal(price),new BigDecimal(amount));
        var key=credential();var account=account(key);var permissions=signed("GET","/sapi/v1/account/apiRestrictions","",key);requireTradingPermissions(account,permissions);
        var commission=signed("GET","/api/v3/account/commission","symbol=BTCUSDT",key);
        if(!commission.path("discount").path("enabledForAccount").isBoolean()||!commission.path("discount").path("enabledForSymbol").isBoolean())throw new IllegalStateException("Binance fee configuration missing");
        if(commission.path("discount").path("enabledForAccount").asBoolean()&&commission.path("discount").path("enabledForSymbol").asBoolean())throw new IllegalArgumentException("Disable Binance BNB fee discount for BTC/USDT acceptance; third-asset valuation is not supported");
    }
    static void requireTradingPermissions(JsonNode account,JsonNode permissions){
        if(!account.path("canTrade").asBoolean()||!permissions.path("enableReading").asBoolean()||!permissions.path("enableSpotAndMarginTrading").asBoolean())throw new IllegalArgumentException("Binance spot trading permission missing");
        for(String field:List.of("enableWithdrawals","enableInternalTransfer","permitsUniversalTransfer","enableMargin","enableFutures","enableVanillaOptions","enablePortfolioMarginTrading"))
            if(!permissions.path(field).isBoolean()||permissions.path(field).asBoolean())throw new IllegalArgumentException("Binance key has excess or unknown permissions: "+field);
    }
    public String placeSpotLimitOrder(String id,String side,String price,String amount){
        requireExecution();validateId(id);validateSpotLimitOrder(side,price,amount);var key=credential();account(key);
        String query="symbol=BTCUSDT&side="+side.toUpperCase(Locale.ROOT)+"&type=LIMIT&timeInForce=GTC&quantity="+plain(new BigDecimal(amount))+"&price="+plain(new BigDecimal(price))+"&newClientOrderId="+id+"&newOrderRespType=ACK";
        return mutation("POST",query,id,key);
    }
    public String cancelOrder(String id){requireExecution();validateId(id);var key=credential();account(key);return mutation("DELETE","symbol=BTCUSDT&origClientOrderId="+id,id,key);}
    private String mutation(String method,String query,String id,LiveCredentialProvider.OkxCredential key){
        var result=signed(method,"/api/v3/order",query,key); // Never retry writes, including exchange timeouts/5xx.
        if(result.has("code"))return JsonUtils.toJsonString(Map.of("code",result.path("code").asText(),"data",List.of(Map.of("sCode",result.path("code").asText(),"sMsg","Binance rejected order"))));
        String echoed=result.path("POST".equals(method)?"clientOrderId":"origClientOrderId").asText();
        if(!id.equals(echoed)||!"BTCUSDT".equals(result.path("symbol").asText()))throw new IllegalStateException("Binance write response identity mismatch");
        return envelope(List.of(Map.of("ordId",required(result,"orderId"),"clOrdId",id,"sCode","0","sMsg","")));
    }
    public String getOrder(String id){
        validateId(id);var key=credential();account(key);var order=signed("GET","/api/v3/order","symbol=BTCUSDT&origClientOrderId="+id,key);requireSymbol(order);
        if(!id.equals(required(order,"clientOrderId")))throw new IllegalStateException("Binance order identity mismatch");
        JsonNode trades=JsonUtils.getObjectMapper().createArrayNode();
        if(nonnegative(order,"executedQty").signum()>0)trades=signed("GET","/api/v3/myTrades","symbol=BTCUSDT&orderId="+required(order,"orderId")+"&limit=1000",key);
        return envelope(List.of(normalizeOrder(order,trades)));
    }
    static Map<String,Object> normalizeOrder(JsonNode order,JsonNode trades){
        requireSymbol(order);BigDecimal filled=nonnegative(order,"executedQty"),quote=nonnegative(order,"cummulativeQuoteQty");
        String state=switch(required(order,"status")){case "NEW","PENDING_NEW"->"live";case "PARTIALLY_FILLED"->"partially_filled";case "FILLED"->"filled";case "CANCELED","EXPIRED","EXPIRED_IN_MATCH"->"canceled";default->"unknown";};
        var out=new LinkedHashMap<String,Object>();out.put("ordId",required(order,"orderId"));out.put("clOrdId",required(order,"clientOrderId"));out.put("sCode","0");out.put("sMsg","");out.put("state",state);out.put("accFillSz",plain(filled));
        out.put("avgPx",filled.signum()>0?plain(quote.divide(filled,18,RoundingMode.HALF_UP)):"");
        BigDecimal qty=BigDecimal.ZERO,cost=BigDecimal.ZERO,fee=BigDecimal.ZERO;String asset="";var ids=new HashSet<String>();boolean complete=trades.isArray()&&trades.size()<1000;
        for(var trade:trades){
            if(!required(order,"orderId").equals(trade.path("orderId").asText())||!"BTCUSDT".equals(trade.path("symbol").asText())||!ids.add(required(trade,"id"))||!trade.path("isBuyer").isBoolean()||trade.path("isBuyer").asBoolean()!= "BUY".equals(order.path("side").asText())){complete=false;continue;}
            qty=qty.add(nonnegative(trade,"qty"));cost=cost.add(nonnegative(trade,"quoteQty"));BigDecimal amount=nonnegative(trade,"commission");String currency=required(trade,"commissionAsset");
            if(amount.signum()>0){if(!asset.isEmpty()&&!asset.equals(currency))complete=false;asset=currency;fee=fee.add(amount);}
        }
        complete&=qty.compareTo(filled)==0&&cost.compareTo(quote)==0;
        // Missing, truncated or mixed-asset fills must remain visibly incomplete, never zero-fee.
        if(complete){out.put("fee",plain(fee.negate()));out.put("feeCcy",asset.isEmpty()?"USDT":asset);out.put("rebate","0");out.put("rebateCcy","USDT");}
        else{out.put("fee","");out.put("feeCcy","");out.put("sMsg","Binance fill cost evidence incomplete");}
        return out;
    }
    public static void validateRules(JsonNode response,BigDecimal price,BigDecimal quantity){
        if(price.signum()<=0||quantity.signum()<=0||response.path("symbols").size()!=1)throw new IllegalArgumentException("Invalid Binance order/rules");var symbol=response.path("symbols").get(0);requireSymbol(symbol);
        if(!"TRADING".equals(symbol.path("status").asText())||!symbol.path("isSpotTradingAllowed").asBoolean())throw new IllegalArgumentException("Binance symbol not available for spot trading");
        boolean priceRule=false,lotRule=false,notionalRule=false,limit=false;for(var type:symbol.path("orderTypes"))if("LIMIT".equals(type.asText()))limit=true;
        for(var filter:symbol.path("filters")){switch(filter.path("filterType").asText()){
            case "PRICE_FILTER"->{rangeAndStep(price,filter,"minPrice","maxPrice","tickSize");priceRule=true;}
            case "LOT_SIZE"->{rangeAndStep(quantity,filter,"minQty","maxQty","stepSize");lotRule=true;}
            case "MIN_NOTIONAL"->{if(price.multiply(quantity).compareTo(nonnegative(filter,"minNotional"))<0)throw new IllegalArgumentException("Binance MIN_NOTIONAL exceeds this order budget");notionalRule=true;}
            case "NOTIONAL"->{BigDecimal value=price.multiply(quantity),min=nonnegative(filter,"minNotional"),max=nonnegative(filter,"maxNotional");if(value.compareTo(min)<0||(max.signum()>0&&value.compareTo(max)>0))throw new IllegalArgumentException("Binance NOTIONAL order budget violation");notionalRule=true;}
            default->{} // Dynamic percent-price/order-count filters remain authoritative at the exchange.
        }}if(!priceRule||!lotRule||!notionalRule||!limit)throw new IllegalArgumentException("Binance mandatory limit filters missing");
    }
    private static void rangeAndStep(BigDecimal value,JsonNode filter,String minKey,String maxKey,String stepKey){BigDecimal min=nonnegative(filter,minKey),max=nonnegative(filter,maxKey),step=nonnegative(filter,stepKey);if(value.compareTo(min)<0||(max.signum()>0&&value.compareTo(max)>0)||(step.signum()>0&&value.remainder(step).signum()!=0))throw new IllegalArgumentException("Binance filter rejected order: "+filter.path("filterType").asText());}
    private void requireExecution(){requireSelected();if(!properties.isLiveExecutionEnabled())throw new IllegalStateException("Live execution disabled");}
    private static void validateId(String id){if(id==null||!id.matches("[A-Za-z0-9_-]{1,36}"))throw new IllegalArgumentException("Invalid Binance client order id");}
    private static void requireSymbol(JsonNode node){if(!"BTCUSDT".equals(node.path("symbol").asText()))throw new IllegalStateException("Binance symbol mismatch");}
    private JsonNode publicGet(String path){return transport("GET",path,null);}
    private JsonNode signed(String method,String path,String query,LiveCredentialProvider.OkxCredential key){
        boolean read="GET".equals(method),resynced=false;
        int attempts=read?properties.getLivePrivateReadMaxAttempts():1;
        for(int attempt=1;attempt<=attempts;attempt++){
            var clock=clock();
            // Server time at receipt is conservative: do not advance by half the round trip.
            long elapsed=nanoTime.getAsLong()-clock.receivedNanos();
            if(elapsed<0||elapsed>=CLOCK_TTL_NANOS)throw new IllegalStateException("Binance server clock expired before signing");
            long timestamp;try{timestamp=Math.addExact(clock.serverMillis(),elapsed/1_000_000);}catch(ArithmeticException e){throw new IllegalStateException("Binance server clock invalid");}
            String payload=(query.isEmpty()?"":query+"&")+"recvWindow=5000&timestamp="+timestamp;
            try{return exchangeOnce(method,path+"?"+payload+"&signature="+sign(payload,key.secretKey()),key.apiKey(),true);}
            catch(BinanceResponseException e){
                if(!read||e.code!=-1021||resynced||attempt==attempts)throw e;
                invalidate(clock);resynced=true;retryPause();
            }catch(IOException e){if(attempt==attempts)throw new IllegalStateException("Binance transport failure after "+attempts+" attempt(s)");retryPause();}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Binance request interrupted");}
        }
        throw new IllegalStateException("Binance request failed");
    }
    private synchronized void invalidate(ServerClock used){if(serverClock==used)serverClock=null;}
    private synchronized ServerClock clock(){
        long now=nanoTime.getAsLong();var cached=serverClock;
        if(cached!=null&&now-cached.receivedNanos()>=0&&now-cached.receivedNanos()<CLOCK_TTL_NANOS)return cached;
        serverClock=null;
        int attempts=properties.getLivePrivateReadMaxAttempts();
        for(int attempt=1;attempt<=attempts;attempt++)try{
            long start=nanoTime.getAsLong();
            // Same private host and proxy as signed operations, without API key or signature.
            var response=exchangeOnce("GET","/api/v3/time",null,true);long end=nanoTime.getAsLong();
            var time=response.path("serverTime");
            if(end-start<0||end-start>MAX_CLOCK_ROUND_TRIP_NANOS||!time.isIntegralNumber()||!time.canConvertToLong()||time.asLong()<=0)throw new IllegalStateException("Binance server clock invalid or response too slow");
            serverClock=new ServerClock(time.asLong(),end);return serverClock;
        }catch(IOException e){if(attempt==attempts)throw new IllegalStateException("Binance clock transport failure after "+attempts+" attempt(s)");retryPause();}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Binance clock request interrupted");}
        throw new IllegalStateException("Binance server clock unavailable");
    }
    private JsonNode transport(String method,String path,String apiKey){
        int attempts="GET".equals(method)?properties.getLivePrivateReadMaxAttempts():1;
        for(int attempt=1;attempt<=attempts;attempt++)try{
            return exchangeOnce(method,path,apiKey,apiKey!=null);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Binance request interrupted");}
        catch(IOException e){if(attempt==attempts)throw new IllegalStateException("Binance transport failure after "+attempts+" attempt(s)");retryPause();}
        throw new IllegalStateException("Binance request failed");
    }
    private JsonNode exchangeOnce(String method,String path,String apiKey,boolean privateHost)throws IOException,InterruptedException{
        String base=privateHost?properties.getLiveBinanceBaseUrl():"https://data-api.binance.vision";
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15)).header("Accept","application/json").header("User-Agent","quant-platform/1.0");if(apiKey!=null)builder.header("X-MBX-APIKEY",apiKey);
        var response=sendTimed(privateHost?http:publicHttp,builder.method(method,HttpRequest.BodyPublishers.noBody()).build(),timingOperation(path));
        if(response.statusCode()>=500||response.statusCode()==429||response.statusCode()==418)throw new IllegalStateException("Binance request uncertain/unavailable HTTP "+response.statusCode());
        JsonNode node;try{node=JsonUtils.getObjectMapper().readTree(response.body());}catch(RuntimeException invalid){throw new IllegalStateException("Binance malformed JSON response");}
        if(node==null)throw new IllegalStateException("Binance malformed JSON response");
        if(node.has("code")||response.statusCode()/100!=2){
            int code=node.path("code").isIntegralNumber()?node.path("code").asInt():Integer.MIN_VALUE;
            if(!"GET".equals(method)&&Set.of(-1013,-1021,-1022,-1100,-1101,-1102,-1111,-1116,-1117,-1118,-1119,-1121,-2010,-2011,-2014,-2015).contains(code))return node;
            throw new BinanceResponseException(code);
        }
        return node;
    }
    private HttpResponse<String> sendTimed(HttpClient client,HttpRequest request,String operation)throws IOException,InterruptedException{
        long start=nanoTime.getAsLong();boolean received=false;String failure="NONE";
        try{var response=client.send(request,HttpResponse.BodyHandlers.ofString());received=true;return response;}
        catch(IOException|InterruptedException e){failure=timingFailure(e);throw e;}
        finally{long millis=Math.max(0,nanoTime.getAsLong()-start)/1_000_000;
            if(millis>=2000||!received)log.warn("Binance request timing operation={} method={} elapsedMillis={} responseReceived={} failureKind={}",operation,request.method(),millis,received,failure);
        }
    }
    // Classify only known types; never include exception messages, class names or signed URIs.
    static String timingFailure(Throwable failure){
        for(int depth=0;failure!=null&&depth<8;depth++,failure=failure.getCause()){
            if(failure instanceof HttpConnectTimeoutException)return "CONNECT_TIMEOUT";
            if(failure instanceof HttpTimeoutException)return "REQUEST_TIMEOUT";
            if(failure instanceof javax.net.ssl.SSLException)return "TLS_FAILURE";
            if(failure instanceof UnknownHostException)return "DNS_FAILURE";
            if(failure instanceof ConnectException)return "CONNECT_FAILURE";
            if(failure instanceof InterruptedException)return "INTERRUPTED";
        }
        return "IO_FAILURE";
    }
    static String timingOperation(String path){return switch(path.split("\\?",2)[0]){
        case "/api/v3/time"->"SERVER_TIME";case "/api/v3/account"->"ACCOUNT";case "/api/v3/openOrders"->"OPEN_ORDERS";
        case "/api/v3/klines"->"CANDLES";case "/api/v3/ticker/bookTicker"->"QUOTE";case "/api/v3/exchangeInfo"->"RULES";
        case "/api/v3/myTrades"->"FILLS";case "/api/v3/order"->"ORDER";
        case "/api/v3/account/commission"->"COMMISSION";case "/sapi/v1/account/apiRestrictions"->"PERMISSIONS";default->"OTHER";
    };}
    private void retryPause(){try{Thread.sleep(properties.getLivePrivateReadRetryDelayMillis());}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Binance retry interrupted");}}
    private static final class BinanceResponseException extends IllegalStateException {
        private final int code;
        private BinanceResponseException(int code){super("Binance request rejected or uncertain code "+(code==Integer.MIN_VALUE?"unavailable":code));this.code=code;}
    }
    public static String sign(String payload,String secret){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("Binance signing failed");}}
    private static String envelope(Object data){return JsonUtils.toJsonString(Map.of("code","0","data",data));}
    private static String required(JsonNode node,String key){String value=node.path(key).asText();if(value.isBlank())throw new IllegalStateException("Binance required field missing: "+key);return value;}
    private static BigDecimal nonnegative(JsonNode node,String key){var value=new BigDecimal(required(node,key));if(value.signum()<0)throw new IllegalStateException("Binance invalid number: "+key);return value;}
    private static BigDecimal positive(JsonNode node,String key){var value=nonnegative(node,key);if(value.signum()==0)throw new IllegalStateException("Binance zero price");return value;}
    private static String plain(BigDecimal value){return value.stripTrailingZeros().toPlainString();}
    private static HttpClient http(String address){var builder=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));if(!address.isBlank()){var proxy=URI.create(address);if(!Set.of("http","https").contains(proxy.getScheme())||proxy.getHost()==null||proxy.getPort()<1||proxy.getUserInfo()!=null)throw new IllegalArgumentException("Invalid exchange proxy");builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(),proxy.getPort())));}return builder.build();}
}
