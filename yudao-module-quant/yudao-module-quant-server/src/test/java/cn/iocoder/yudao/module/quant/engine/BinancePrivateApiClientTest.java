package cn.iocoder.yudao.module.quant.engine;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import javax.net.ssl.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class BinancePrivateApiClientTest {
    static final String ACCOUNT="{\"uid\":12345,\"accountType\":\"SPOT\",\"canTrade\":true,\"balances\":[{\"asset\":\"BTC\",\"free\":\"0.0001\",\"locked\":\"0.0002\"},{\"asset\":\"USDT\",\"free\":\"10\",\"locked\":\"1\"}]}";
    static final String PERMISSIONS="{\"enableReading\":true,\"enableSpotAndMarginTrading\":true,\"enableWithdrawals\":false,\"enableInternalTransfer\":false,\"permitsUniversalTransfer\":false,\"enableMargin\":false,\"enableFutures\":false,\"enableVanillaOptions\":false,\"enablePortfolioMarginTrading\":false,\"ipRestrict\":true}";
    static final String RULES="{\"symbols\":[{\"symbol\":\"BTCUSDT\",\"status\":\"TRADING\",\"isSpotTradingAllowed\":true,\"orderTypes\":[\"LIMIT\"],\"filters\":[{\"filterType\":\"PRICE_FILTER\",\"minPrice\":\"0.01\",\"maxPrice\":\"1000000\",\"tickSize\":\"0.01\"},{\"filterType\":\"LOT_SIZE\",\"minQty\":\"0.00001\",\"maxQty\":\"9000\",\"stepSize\":\"0.00001\"},{\"filterType\":\"NOTIONAL\",\"minNotional\":\"5\",\"maxNotional\":\"1000000\"}]}]}";
    static final String COMMISSION="{\"discount\":{\"enabledForAccount\":false,\"enabledForSymbol\":true}}";
    static final String ORDER="{\"symbol\":\"BTCUSDT\",\"orderId\":9,\"clientOrderId\":\"qa1\",\"status\":\"FILLED\",\"side\":\"BUY\",\"executedQty\":\"0.0001\",\"cummulativeQuoteQty\":\"10\"}";
    static String fill(int id,String qty,String quote,String fee,String asset){return "{\"symbol\":\"BTCUSDT\",\"id\":"+id+",\"orderId\":9,\"isBuyer\":true,\"qty\":\""+qty+"\",\"quoteQty\":\""+quote+"\",\"commission\":\""+fee+"\",\"commissionAsset\":\""+asset+"\"}";}
    @Test void officialHmacVectorAndCredentialExchangeIsolation(){
        assertEquals("c8db56825ae71d6d79447849e617115f4a920fa2acdcab2b053c4b2838bd6b71",BinancePrivateApiClient.sign("symbol=LTCBTC&side=BUY&type=LIMIT&timeInForce=GTC&quantity=1&price=0.1&recvWindow=5000&timestamp=1499827319559","NhqPtmdSJYdKjVHjA7PZj4Mge3R5YNiP1e3UZjInClVN65XAbvqqM6A7H5fATj0j"));
        assertThrows(IllegalStateException.class,()->DpapiLiveCredentialProvider.decode("{\"apiKey\":\"a\",\"secretKey\":\"s\",\"passphrase\":\"p\"}","binance"));
        var value=DpapiLiveCredentialProvider.decode("{\"exchange\":\"binance\",\"apiKey\":\"a\",\"secretKey\":\"s\"}","binance");assertEquals("",value.passphrase());assertFalse(value.toString().contains("secret"));
    }
    @Test void filterPrecisionAndBudgetAreNotSilentlyRoundedUp(){
        var rules=json(RULES);BinancePrivateApiClient.validateRules(rules,new java.math.BigDecimal("100000"),new java.math.BigDecimal("0.00005"));
        for(String amount:List.of("0.00004","0.000051"))assertThrows(IllegalArgumentException.class,()->BinancePrivateApiClient.validateRules(rules,new java.math.BigDecimal("100000"),new java.math.BigDecimal(amount)));
        assertThrows(IllegalArgumentException.class,()->BinancePrivateApiClient.validateRules(rules,new java.math.BigDecimal("100000.001"),new java.math.BigDecimal("0.0001")));
        assertThrows(IllegalArgumentException.class,()->BinancePrivateApiClient.validateRules(json(RULES.replace("TRADING","BREAK")),new java.math.BigDecimal("100000"),new java.math.BigDecimal("0.0001")));
    }
    @Test void automatedTermsRespectPriceAndQuantityStepsWithoutBuyingExtraInventory(){
        var http=new FakeHttp(RULES,RULES,RULES);var client=client(http);
        assertEquals(new java.math.BigDecimal("100000.01"),client.limitPrice("BUY",new java.math.BigDecimal("100000.019")));
        assertEquals(new java.math.BigDecimal("100000.02"),client.limitPrice("SELL",new java.math.BigDecimal("100000.019")));
        assertEquals(new java.math.BigDecimal("0.00005"),client.limitAmount(new java.math.BigDecimal("0.00005931")));
    }
    @Test void rejectExcessAndMissingPermissions(){
        BinancePrivateApiClient.requireTradingPermissions(json(ACCOUNT),json(PERMISSIONS));
        assertThrows(IllegalArgumentException.class,()->BinancePrivateApiClient.requireTradingPermissions(json(ACCOUNT),json(PERMISSIONS.replace("\"enableWithdrawals\":false","\"enableWithdrawals\":true"))));
        assertThrows(IllegalArgumentException.class,()->BinancePrivateApiClient.requireTradingPermissions(json(ACCOUNT),json("{}")));
    }
    @Test void balanceIncludesLockedBtcExposureAndSignedReadsRetryWithoutLeakingSecrets(){
        var http=new FakeHttp(new IOException("secret uri"),ACCOUNT,"{\"bidPrice\":\"100000\"}");var client=client(http);var balance=json(client.accountBalance());assertEquals("41",balance.path("data").get(0).path("totalEq").asText());assertEquals("30",balance.path("data").get(0).path("details").get(0).path("eqUsd").asText());assertEquals(3,http.requests.size());assertTrue(http.requests.get(0).uri().getRawQuery().contains("recvWindow=5000"));assertTrue(http.requests.get(0).headers().firstValue("X-MBX-APIKEY").isPresent());assertTrue(http.requests.get(2).headers().firstValue("X-MBX-APIKEY").isEmpty());
    }
    @Test void costsAggregateExactlyAndIncompleteOrMixedFillsStayIncomplete(){
        String one=fill(1,"0.00004","4","0.00000004","BTC"),two=fill(2,"0.00006","6","0.00000006","BTC");
        var result=BinancePrivateApiClient.normalizeOrder(json(ORDER),json("["+one+","+two+"]"));assertEquals("-0.0000001",result.get("fee"));assertEquals("BTC",result.get("feeCcy"));assertEquals("100000",result.get("avgPx"));
        for(String rows:List.of("[]","["+one+"]","["+one+","+one+"]","["+one+","+two.replace("BTC\"}","USDT\"}")+"]"))assertEquals("",BinancePrivateApiClient.normalizeOrder(json(ORDER),json(rows)).get("fee"));
        var bnb=BinancePrivateApiClient.normalizeOrder(json(ORDER),json("["+fill(1,"0.0001","10","0.00001","BNB")+"]"));assertEquals("BNB",bnb.get("feeCcy"));assertEquals("-0.00001",bnb.get("fee"));
    }
    @Test void refusesWrongAccountAndDisabledWritesBeforeCredentialsLoad(){
        var props=props();props.setLiveExchange("okx");var provider=new LiveCredentialProvider(){public boolean configured(){return true;}public Optional<OkxCredential> load(){throw new AssertionError();}};var http=new FakeHttp();var client=new BinancePrivateApiClient(props,provider,null,http);assertFalse(client.configured());assertThrows(IllegalStateException.class,client::accountBalance);assertThrows(IllegalStateException.class,()->client.cancelOrder("qa1"));assertEquals(0,http.requests.size());
    }
    @Test void placementAndCancellationAreSingleAttemptAndTimeoutsRemainUncertain(){
        var http=new FakeHttp(RULES,ACCOUNT,PERMISSIONS,COMMISSION,ACCOUNT,new IOException("signed uri secret"));var client=client(http);var placementClient=client;var error=assertThrows(IllegalStateException.class,()->placementClient.placeSpotLimitOrder("qa1","BUY","100000","0.0001"));assertEquals(1,http.requests.stream().filter(r->r.method().equals("POST")).count());assertFalse(error.getMessage().contains("secret"));assertNull(error.getCause());
        http=new FakeHttp(ACCOUNT,new IOException("cancel"));client=client(http);var cancelClient=client;assertThrows(IllegalStateException.class,()->cancelClient.cancelOrder("qa1"));assertEquals(1,http.requests.stream().filter(r->r.method().equals("DELETE")).count());
        http=new FakeHttp(ACCOUNT,new Reply(400,"{\"code\":-1007,\"msg\":\"timeout\"}"));var unknown=client(http);assertThrows(IllegalStateException.class,()->unknown.cancelOrder("qa1"));
    }
    @Test void bnbDiscountBlocksNewOrderBeforePost(){var http=new FakeHttp(RULES,ACCOUNT,PERMISSIONS,COMMISSION.replace("false","true"));var client=client(http);assertThrows(IllegalArgumentException.class,()->client.placeSpotLimitOrder("qa1","BUY","100000","0.0001"));assertTrue(http.requests.stream().noneMatch(r->r.method().equals("POST")));}
    @Test void identityRecheckedAfterRollbackAndDifferentUidRejected(){
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));jdbc.execute("CREATE TABLE quant_exchange_account(id VARCHAR PRIMARY KEY,exchange_name VARCHAR,credential_file_name VARCHAR,identity_hash VARCHAR UNIQUE)");jdbc.update("INSERT INTO quant_exchange_account VALUES('binance-primary','binance','binance-live.dpapi',NULL)");
        var props=props();props.setWorkspace("D:/0000/quant-platform/.runtime/quant");props.setLiveAccountId("binance-primary");props.setLiveCredentialFile(props.getWorkspace()+"/credentials/binance-live.dpapi");var repo=new cn.iocoder.yudao.module.quant.dal.ExchangeAccountRepository(jdbc,props);var http=new FakeHttp(ACCOUNT,"[]",ACCOUNT,"[]",ACCOUNT.replace("12345","98765"));var client=new BinancePrivateApiClient(props,credentials(),repo,http);
        var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->{client.pendingOrders();throw new IllegalStateException("rollback");}));assertNull(repo.current().get("identityHash"));client.pendingOrders();assertNotNull(repo.current().get("identityHash"));assertThrows(IllegalArgumentException.class,client::pendingOrders);assertEquals(5,http.requests.size());
    }
    @Test void getOrderChecksClientAndFillOwnership(){var http=new FakeHttp(ACCOUNT,ORDER,"["+fill(1,"0.0001","10","0.01","USDT")+"]");var result=json(client(http).getOrder("qa1"));assertEquals("filled",result.path("data").get(0).path("state").asText());assertEquals("-0.01",result.path("data").get(0).path("fee").asText());assertTrue(http.requests.get(2).uri().getRawQuery().contains("orderId=9"));assertThrows(IllegalStateException.class,()->client(new FakeHttp(ACCOUNT,ORDER.replace("qa1","foreign"))).getOrder("qa1"));}
    @Test void confirmedRejectionIsDistinguishedFromUnknownWriteAndAccountVerificationSendsNoOrders(){
        var http=new FakeHttp(ACCOUNT,new Reply(400,"{\"code\":-2011,\"msg\":\"unknown order\"}"));var result=json(client(http).cancelOrder("qa1"));assertEquals("-2011",result.path("code").asText());assertEquals(1,http.requests.stream().filter(r->r.method().equals("DELETE")).count());
        http=new FakeHttp(ACCOUNT,PERMISSIONS);var summary=client(http).verifyReadOnly();assertEquals(0,summary.get("ordersSent"));assertEquals(false,summary.get("enableWithdrawals"));assertFalse(summary.containsKey("uid"));assertTrue(http.requests.stream().allMatch(r->r.method().equals("GET")));
        var validator=jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();assertTrue(validator.validate(new cn.iocoder.yudao.module.quant.api.backtest.LivePrivateReadRequest("CONFIRM_BINANCE_PRIVATE_READ","review")).isEmpty());
    }
    static JsonNode json(String value){return JsonUtils.getObjectMapper().readTree(value);}
    static QuantProperties props(){var p=new QuantProperties();p.setLiveExchange("binance");p.setLiveExecutionEnabled(true);p.setLivePrivateReadRetryDelayMillis(1);return p;}
    static LiveCredentialProvider credentials(){return new LiveCredentialProvider(){public boolean configured(){return true;}public Optional<OkxCredential> load(){return Optional.of(new OkxCredential("fake-key","fake-secret",""));}};}
    static BinancePrivateApiClient client(FakeHttp http){return new BinancePrivateApiClient(props(),credentials(),null,http);}
    record Reply(int status,String body){}
    static class FakeHttp extends HttpClient {
        final Queue<Object> outcomes=new ArrayDeque<>();final List<HttpRequest> requests=new ArrayList<>();FakeHttp(Object... values){outcomes.addAll(List.of(values));}
        @SuppressWarnings("unchecked") public <T> HttpResponse<T> send(HttpRequest request,HttpResponse.BodyHandler<T> handler)throws IOException {requests.add(request);var next=outcomes.remove();if(next instanceof IOException error)throw error;var reply=next instanceof Reply value?value:new Reply(200,(String)next);return (HttpResponse<T>)new HttpResponse<String>(){public int statusCode(){return reply.status();}public String body(){return reply.body();}public HttpRequest request(){return request;}public Optional<HttpResponse<String>> previousResponse(){return Optional.empty();}public HttpHeaders headers(){return HttpHeaders.of(Map.of(),(a,b)->true);}public Optional<SSLSession> sslSession(){return Optional.empty();}public URI uri(){return request.uri();}public Version version(){return Version.HTTP_2;}};}
        public Optional<CookieHandler> cookieHandler(){return Optional.empty();}public Optional<Duration> connectTimeout(){return Optional.empty();}public Redirect followRedirects(){return Redirect.NEVER;}public Optional<ProxySelector> proxy(){return Optional.empty();}public SSLContext sslContext(){return null;}public SSLParameters sslParameters(){return new SSLParameters();}public Optional<Authenticator> authenticator(){return Optional.empty();}public Version version(){return Version.HTTP_2;}public Optional<Executor> executor(){return Optional.empty();}
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h){throw new UnsupportedOperationException();}public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h,HttpResponse.PushPromiseHandler<T> p){throw new UnsupportedOperationException();}
    }
}
