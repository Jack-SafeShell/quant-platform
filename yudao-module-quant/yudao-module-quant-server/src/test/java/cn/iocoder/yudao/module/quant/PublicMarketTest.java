package cn.iocoder.yudao.module.quant;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.service.PublicMarketService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;
class PublicMarketTest {
    static JsonNode json(String s){return JsonUtils.getObjectMapper().readTree(s);}
    static String candle(long at){return "["+at+",\"100\",\"110\",\"90\",\"105\",\"1\","+(at+3599999)+"]";}
    static JsonNode book(){return json("{\"bidPrice\":\"100\",\"askPrice\":\"101\"}");}
    @Test void excludesUnclosedBinanceCandleAndNormalizesUtcOrder(){
        var result=PublicMarketService.normalize("binance",10800000,book(),json("["+candle(7200000)+","+candle(10800000)+","+candle(3600000)+"]"));
        assertEquals(7200000L,result.get("lastClosedCandleAt"));assertEquals(2,((java.util.List<?>)result.get("closedCandles")).size());assertEquals(true,result.get("publicOnly"));
    }
    @Test void rejectsDuplicateOrMissingClosedCandles(){
        assertThrows(IllegalArgumentException.class,()->PublicMarketService.normalize("binance",10800000,book(),json("["+candle(7200000)+","+candle(7200000)+"]")));
        assertThrows(IllegalArgumentException.class,()->PublicMarketService.normalize("binance",10800000,book(),json("["+candle(0)+","+candle(7200000)+"]")));
    }
    @Test void rejectsWrongCloseTimeAndInvalidPrices(){
        assertThrows(IllegalArgumentException.class,()->PublicMarketService.normalize("binance",10800000,book(),json("["+candle(7200000).replace("10799999","10799998")+"]")));
        assertThrows(IllegalArgumentException.class,()->PublicMarketService.normalize("binance",10800000,book(),json("["+candle(7200000).replace("110","95")+"]")));
        assertThrows(IllegalArgumentException.class,()->PublicMarketService.normalize("binance",10800000,json("{\"bidPrice\":\"102\",\"askPrice\":\"101\"}"),json("["+candle(7200000)+"]")));
    }
    @Test void rejectsStaleOrEmptyCandles(){
        assertThrows(IllegalArgumentException.class,()->PublicMarketService.normalize("binance",18000000,book(),json("["+candle(0)+"]")));
        assertThrows(IllegalArgumentException.class,()->PublicMarketService.normalize("binance",10800000,book(),json("[]")));
    }
    @Test void preservesOkxConfirmationSemantics(){
        var r=PublicMarketService.normalize("okx",10800000,json("{\"code\":\"0\",\"data\":[{\"bidPx\":\"100\",\"askPx\":\"101\"}]}"),json("{\"code\":\"0\",\"data\":[[7200000,\"100\",\"110\",\"90\",\"105\",\"1\",\"0\",\"0\",\"1\"],[10800000,\"100\",\"110\",\"90\",\"105\",\"1\",\"0\",\"0\",\"0\"]]}"));
        assertEquals(7200000L,r.get("lastClosedCandleAt"));
    }
}
