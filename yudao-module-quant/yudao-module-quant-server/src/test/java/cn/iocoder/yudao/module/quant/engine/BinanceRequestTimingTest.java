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
}
