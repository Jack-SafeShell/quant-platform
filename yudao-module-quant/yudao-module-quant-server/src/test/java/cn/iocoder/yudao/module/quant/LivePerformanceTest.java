package cn.iocoder.yudao.module.quant;
import cn.iocoder.yudao.module.quant.service.LivePerformanceService;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class LivePerformanceTest {
    Map<String,Object> order(String side,String amount,String price,String fee,String currency,String rebate,String rebateCurrency) {
        var r=new HashMap<String,Object>();r.put("side",side);r.put("status","FILLED");r.put("filledAmount",new BigDecimal(amount));r.put("averagePrice",new BigDecimal(price));r.put("feeAmount",fee==null?null:new BigDecimal(fee));r.put("feeCurrency",currency);r.put("rebateAmount",rebate==null?null:new BigDecimal(rebate));r.put("rebateCurrency",rebateCurrency);return r;
    }
    void amount(String expected,Object actual){assertNotNull(actual);assertEquals(0,new BigDecimal(expected).compareTo((BigDecimal)actual));}
    @Test void roundTripIncludesBaseFeesAndQuoteFeesAndRebates(){
        var rows=List.of(order("BUY","1","100","-0.01","BTC","0",""),order("SELL","0.99","120","-1","USDT","0.2","USDT"));
        var r=LivePerformanceService.calculate(rows,null,null);
        amount("0",r.get("netPositionBtc"));amount("18",r.get("netContribution"));amount("-1.8",r.get("knownSignedCostsUsdt"));assertEquals(true,r.get("valuationComplete"));
    }
    @Test void partialCanceledFillAndOpenInventoryUseClosedCandleMark(){
        var row=order("BUY","0.5","100","-0.05","USDT","0","BTC");row.put("status","CANCELED");
        var r=LivePerformanceService.calculate(List.of(row),new BigDecimal("110"),123L);
        amount("4.95",r.get("netContribution"));amount("55",r.get("markedPositionValue"));assertEquals(1,r.get("filledOrderCount"));
        assertNull(LivePerformanceService.calculate(List.of(row),null,null).get("netContribution"));
    }
    @Test void missingAndUnsupportedCostsCannotBecomeZeroFeeProfit(){
        var missing=order("BUY","1","100",null,"",null,"");var unsupported=order("BUY","1","100","-0.1","OKB","0","");
        for(var row:List.of(missing,unsupported)) {var r=LivePerformanceService.calculate(List.of(row),new BigDecimal("110"),123L);assertNull(r.get("netContribution"));assertNull(r.get("netPositionBtc"));assertEquals(false,r.get("valuationComplete"));assertEquals(1,r.get("missingCostOrderCount"));}
    }
    @Test void emptyCanceledOrdersAndInvalidSellInventoryAreExplicit(){
        var empty=order("BUY","0","0",null,"",null,"");empty.put("status","CANCELED");
        amount("0",LivePerformanceService.calculate(List.of(empty),null,null).get("netContribution"));
        var r=LivePerformanceService.calculate(List.of(order("SELL","1","120","0","USDT","0","")),new BigDecimal("100"),123L);
        assertNull(r.get("netContribution"));assertEquals(false,r.get("valuationComplete"));
    }
}
