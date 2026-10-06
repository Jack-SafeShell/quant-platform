package cn.iocoder.yudao.module.quant.engine;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import static cn.iocoder.yudao.module.quant.engine.BinancePrivateApiClientTest.*;
import static org.junit.jupiter.api.Assertions.*;

class BinanceRequestTimingTest {
    @Test void slowAndFailedRequestsLogOnlyFixedOperationAndElapsedTime(){
        var logger=(Logger)LoggerFactory.getLogger(BinancePrivateApiClient.class);var appender=new ListAppender<ILoggingEvent>();appender.start();logger.addAppender(appender);
        try{
            var nanos=new AtomicLong();var http=new FakeHttp(new IOException("sensitive-uri-and-secret"),ACCOUNT,"{\"bidPrice\":\"100000\"}");
            http.onRequest=r->{if(r.headers().firstValue("X-MBX-APIKEY").isPresent())nanos.addAndGet(3_000_000_000L);};
            new BinancePrivateApiClient(props(),credentials(),null,http,nanos::get).accountBalance();
            assertEquals(2,appender.list.size());String output=appender.list.toString();
            for(var event:appender.list){assertNull(event.getThrowableProxy());assertTrue(event.getFormattedMessage().contains("operation=ACCOUNT"));assertTrue(event.getFormattedMessage().contains("elapsedMillis=3000"));}
            assertFalse(output.contains("sensitive-uri"));assertFalse(output.contains("fake-secret"));assertFalse(output.contains("signature="));assertFalse(output.contains("timestamp="));
        }finally{logger.detachAppender(appender);appender.stop();}
    }
    @Test void operationLabelsDiscardQueryAndUnknownPaths(){
        assertEquals("ORDER",BinancePrivateApiClient.timingOperation("/api/v3/order?signature=private-secret&origClientOrderId=private-id"));
        assertEquals("OTHER",BinancePrivateApiClient.timingOperation("/unknown/private-secret?signature=x"));
        assertEquals("SERVER_TIME",BinancePrivateApiClient.timingOperation("/api/v3/time"));
    }
    @Test void transportFailureCategoriesDoNotIncludePrivateDetails(){
        assertEquals("CONNECT_TIMEOUT",BinancePrivateApiClient.timingFailure(new java.net.http.HttpConnectTimeoutException("secret")));
        assertEquals("REQUEST_TIMEOUT",BinancePrivateApiClient.timingFailure(new java.net.http.HttpTimeoutException("signature=secret")));
        assertEquals("TLS_FAILURE",BinancePrivateApiClient.timingFailure(new javax.net.ssl.SSLHandshakeException("secret")));
        assertEquals("DNS_FAILURE",BinancePrivateApiClient.timingFailure(new IOException("secret",new java.net.UnknownHostException("secret-host"))));
        assertEquals("CONNECT_FAILURE",BinancePrivateApiClient.timingFailure(new java.net.ConnectException("secret")));
        assertEquals("INTERRUPTED",BinancePrivateApiClient.timingFailure(new InterruptedException("secret")));
        assertEquals("IO_FAILURE",BinancePrivateApiClient.timingFailure(new IOException("private-response")));
    }
    @Test void clockTimeoutLoggingPreservesBoundedReadsAndSuppressesExceptionDetails(){
        var logger=(Logger)LoggerFactory.getLogger(BinancePrivateApiClient.class);var appender=new ListAppender<ILoggingEvent>();appender.start();logger.addAppender(appender);
        try{
            var http=new FakeHttp();
            for(int i=0;i<3;i++)http.clockOutcomes.add(new java.net.http.HttpTimeoutException("signature=private-secret"));
            assertThrows(IllegalStateException.class,()->new BinancePrivateApiClient(props(),credentials(),null,http).accountBalance());
            assertEquals(3,http.requests.size());
            assertEquals(3,appender.list.size());
            assertTrue(http.requests.stream().allMatch(r->r.method().equals("GET")&&r.uri().getPath().equals("/api/v3/time")&&r.headers().firstValue("X-MBX-APIKEY").isEmpty()));
            for(var event:appender.list){assertNull(event.getThrowableProxy());assertTrue(event.getFormattedMessage().contains("failureKind=REQUEST_TIMEOUT"));assertFalse(event.getFormattedMessage().contains("private-secret"));}
        }finally{logger.detachAppender(appender);appender.stop();}
    }
}
