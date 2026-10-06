package cn.iocoder.yudao.module.quant.engine;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static cn.iocoder.yudao.module.quant.engine.BinancePrivateApiClientTest.*;
import static org.junit.jupiter.api.Assertions.*;

class BinanceClockTest {
    static final long SERVER=1710000000000L;
    static final Reply TIMESTAMP=new Reply(400,"{\"code\":-1021,\"msg\":\"signed uri fake-secret\"}");
    static List<HttpRequest> signed(FakeHttp http){return http.requests.stream().filter(r->r.headers().firstValue("X-MBX-APIKEY").isPresent()).toList();}
    static long clocks(FakeHttp http){return http.requests.stream().filter(r->r.uri().getPath().equals("/api/v3/time")).count();}
    static long timestamp(HttpRequest r){return Arrays.stream(r.uri().getRawQuery().split("&")).filter(p->p.startsWith("timestamp=")).map(p->Long.parseLong(p.substring(10))).findFirst().orElseThrow();}
    static BinancePrivateApiClient client(FakeHttp http,AtomicLong nanos){return new BinancePrivateApiClient(props(),credentials(),null,http,nanos::get);}
    @Test void signsWithPrivateServerTimeAndMonotonicElapsedWithoutSendingClockCredentials(){
        var nanos=new AtomicLong();var http=new FakeHttp(ACCOUNT,"{\"bidPrice\":\"100000\"}",ACCOUNT,"{\"bidPrice\":\"100000\"}");
        var client=client(http,nanos);client.accountBalance();nanos.addAndGet(2_000_000_000L);client.accountBalance();
        assertEquals(SERVER,timestamp(signed(http).getFirst()));assertEquals(SERVER+2000,timestamp(signed(http).getLast()));assertEquals(1,clocks(http));
        var clock=http.requests.getFirst();assertEquals("api.binance.com",clock.uri().getHost());assertNull(clock.uri().getQuery());assertTrue(clock.headers().firstValue("X-MBX-APIKEY").isEmpty());
    }
    @Test void retryAfterDelayedTransportSignsAgainRatherThanReusingExpiredUrl(){
        var nanos=new AtomicLong();var http=new FakeHttp(new IOException("signed uri fake-secret"),ACCOUNT,"{\"bidPrice\":\"100000\"}");
        http.onRequest=r->{if(r.headers().firstValue("X-MBX-APIKEY").isPresent()&&signed(http).size()==1)nanos.addAndGet(6_000_000_000L);};
        client(http,nanos).accountBalance();var reads=signed(http);assertEquals(2,reads.size());assertEquals(SERVER,timestamp(reads.getFirst()));assertEquals(SERVER+6000,timestamp(reads.getLast()));assertNotEquals(reads.getFirst().uri(),reads.getLast().uri());
        for(var read:reads){var query=read.uri().getRawQuery();int at=query.indexOf("&signature=");assertEquals(BinancePrivateApiClient.sign(query.substring(0,at),"fake-secret"),query.substring(at+11));assertTrue(query.contains("recvWindow=5000"));}
    }
    @Test void timestampReadRejectionResyncsOnceWithinAttemptBudget(){
        var http=new FakeHttp(TIMESTAMP,ACCOUNT,"{\"bidPrice\":\"100000\"}");http.clockOutcomes.add("{\"serverTime\":"+SERVER+"}");http.clockOutcomes.add("{\"serverTime\":"+(SERVER+2000)+"}");
        client(http,new AtomicLong()).accountBalance();assertEquals(2,clocks(http));assertEquals(2,signed(http).size());assertEquals(SERVER+2000,timestamp(signed(http).getLast()));
        http=new FakeHttp(TIMESTAMP,TIMESTAMP,ACCOUNT);var blocked=http;var error=assertThrows(IllegalStateException.class,()->client(blocked,new AtomicLong()).accountBalance());assertEquals(2,clocks(http));assertEquals(2,signed(http).size());assertFalse(error.getMessage().contains("fake-secret"));assertNull(error.getCause());
    }
    @Test void mixedTransportAndTimestampFailuresHaveOneSharedSignedReadBudget(){
        var http=new FakeHttp(new IOException("secret"),TIMESTAMP,new IOException("secret"),ACCOUNT);
        var error=assertThrows(IllegalStateException.class,()->client(http,new AtomicLong()).accountBalance());assertEquals(3,signed(http).size());assertEquals(2,clocks(http));assertNull(error.getCause());
        var disabled=new FakeHttp(TIMESTAMP,ACCOUNT);var p=props();p.setLivePrivateReadMaxAttempts(1);var c=new BinancePrivateApiClient(p,credentials(),null,disabled,()->0L);assertThrows(IllegalStateException.class,c::accountBalance);assertEquals(1,signed(disabled).size());assertEquals(1,clocks(disabled));
    }
    @Test void writeTimestampRejectionsDoNotResyncOrSendWriteAgain(){
        for(boolean place:List.of(true,false)){
            var http=place?new FakeHttp(RULES,ACCOUNT,PERMISSIONS,COMMISSION,ACCOUNT,TIMESTAMP):new FakeHttp(ACCOUNT,TIMESTAMP);
            var c=client(http,new AtomicLong());var result=json(place?c.placeSpotLimitOrder("qa1","BUY","100000","0.0001"):c.cancelOrder("qa1"));
            assertEquals("-1021",result.path("code").asText());assertEquals(1,http.requests.stream().filter(r->!r.method().equals("GET")).count());assertEquals(1,clocks(http));
        }
    }
    @Test void invalidOrSlowClockFailsBeforeSignedCallWithoutLocalTimeFallback(){
        for(String response:List.of("{}","null","{\"serverTime\":-1}","{\"serverTime\":\"1710000000000\"}","{\"serverTime\":99999999999999999999999999}")){
            var http=new FakeHttp(ACCOUNT);http.clockOutcomes.add(response);assertThrows(IllegalStateException.class,()->client(http,new AtomicLong()).accountBalance());assertEquals(0,signed(http).size());
        }
        var nanos=new AtomicLong();var http=new FakeHttp(ACCOUNT);http.onRequest=r->nanos.addAndGet(2_501_000_000L);assertThrows(IllegalStateException.class,()->client(http,nanos).accountBalance());assertEquals(0,signed(http).size());
    }
    @Test void expiredClockAndRejectedResyncCannotUseOldCalibration(){
        var nanos=new AtomicLong();var http=new FakeHttp(ACCOUNT,"{\"bidPrice\":\"100000\"}",ACCOUNT);http.clockOutcomes.add("{\"serverTime\":"+SERVER+"}");http.clockOutcomes.add("{}");
        var c=client(http,nanos);c.accountBalance();nanos.addAndGet(60_000_000_000L);assertThrows(IllegalStateException.class,c::accountBalance);assertEquals(1,signed(http).size());
        var rejected=new FakeHttp(TIMESTAMP,ACCOUNT);rejected.clockOutcomes.add("{\"serverTime\":"+SERVER+"}");rejected.clockOutcomes.add(new Reply(503,"secret"));assertThrows(IllegalStateException.class,()->client(rejected,new AtomicLong()).accountBalance());assertEquals(1,signed(rejected).size());
    }
    @Test void clockTransportRetryIsBoundedAndKeepsSignedRequestsUnsent(){
        var http=new FakeHttp(ACCOUNT);http.clockOutcomes.addAll(List.of(new IOException("secret"),new IOException("secret"),new IOException("secret")));
        var error=assertThrows(IllegalStateException.class,()->client(http,new AtomicLong()).accountBalance());assertEquals(3,clocks(http));assertEquals(0,signed(http).size());assertNull(error.getCause());assertFalse(error.getMessage().contains("secret"));
    }
}
