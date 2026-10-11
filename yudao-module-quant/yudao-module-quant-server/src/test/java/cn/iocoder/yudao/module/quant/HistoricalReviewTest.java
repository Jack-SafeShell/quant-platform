package cn.iocoder.yudao.module.quant;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.service.HistoricalReview;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HistoricalReviewTest {
    final long start=Instant.parse("2025-01-31T23:00:00Z").toEpochMilli();
    List<List<Number>> candles() { return List.of(List.of(start,100,110,80,90,10),
            List.of(start+3600000,90,120,80,110,10),List.of(start+7200000,110,130,100,120,10)); }
    Map<String,Object> trade(long open, long close, String reason) {
        return new LinkedHashMap<>(Map.of("instrument","BTC/USDT","openedAt",Instant.ofEpochMilli(open).toString(),
                "closedAt",Instant.ofEpochMilli(close).toString(),"amount",1,"open_rate",100,"close_rate",110,
                "fee_open",.001,"fee_close",.001,"netProfit",9.79,"exitReason",reason));
    }
    String result(List<Map<String,Object>> trades,double profit) {
        trades.forEach(t->{t.putIfAbsent("isShort",false);t.putIfAbsent("isOpen",false);});
        return JsonUtils.toJsonString(Map.of("totalTrades",trades.size(),"netProfit",profit,"returnRatio",profit/1000,"trades",trades));
    }
    Map<String,Object> review(String result) { return HistoricalReview.calculate(result,1000,.001,start,start+10800000,candles()); }
    @Test void reconstructsHeldLossBeforeRealizationAndFeeAdjustedBenchmark() {
        var r=review(result(List.of(trade(start,start+7200000,"exit_signal")),9.79));
        assertEquals(true,r.get("available"));
        var points=(List<HistoricalReview.Point>)r.get("points");
        assertEquals(1000,points.getFirst().equity(),1e-8);
        assertEquals(989.81,points.get(1).equity(),1e-8);
        assertEquals(.01019,points.get(1).drawdownRatio(),1e-8);
        assertEquals(1009.79,points.getLast().equity(),1e-8);
        assertEquals(1000/100.1*120*.999,points.getLast().buyHold(),1e-8);
        var months=(List<HistoricalReview.Month>)r.get("months");
        assertEquals(0,months.getFirst().realizedNetProfit(),1e-8);
        assertEquals("2025-02",months.getLast().month());
        assertEquals(9.79,months.getLast().realizedNetProfit(),1e-8);
    }
    @Test void emptyTradesStayFlatWithZeroMonthlyRealization() {
        var r=review(result(List.of(),0));assertEquals(true,r.get("available"));
        ((List<HistoricalReview.Point>)r.get("points")).forEach(p->assertEquals(1000,p.equity(),1e-8));
        assertEquals(2,((List<?>)r.get("months")).size());
    }
    @Test void accountsForSameCandleRoundTripAndEndSettlement() {
        var t=trade(start,start,"roi");
        var r=review(result(List.of(t),9.79));assertEquals(true,r.get("available"));
        assertEquals(1009.79,((List<HistoricalReview.Point>)r.get("points")).get(1).equity(),1e-8);
        t=trade(start,start+10800000,"force_exit");t.put("isOpen",true);
        assertEquals(true,review(result(List.of(t),9.79)).get("available"));
    }
    @Test void refusesMissingOrInconsistentProfitEvidence() {
        assertEquals(false,review("{\"totalTrades\":0,\"netProfit\":0}").get("available"));
        assertEquals(false,review(result(List.of(trade(start,start+7200000,"roi")),10)).get("available"));
        var t=trade(start,start+7200000,"roi");t.remove("amount");
        assertEquals(false,review(result(List.of(t),9.79)).get("available"));
        t=trade(start,start+7200000,"roi");t.put("netProfit",10);
        assertEquals(false,review(result(List.of(t),10)).get("available"));
    }
    @Test void rejectsForeignPeriodShortOpenOverlapAndUnfundedTrades() {
        var t=trade(start-3600000,start+7200000,"roi");assertEquals(false,review(result(List.of(t),9.79)).get("available"));
        t=trade(start,start+7200000,"roi");t.put("isShort",true);assertEquals(false,review(result(List.of(t),9.79)).get("available"));
        t.put("isShort",false);t.put("isOpen",true);assertEquals(false,review(result(List.of(t),9.79)).get("available"));
        assertEquals(false,review(result(List.of(trade(start,start+7200000,"roi"),trade(start+3600000,start+10800000,"roi")),19.58)).get("available"));
        t=trade(start,start+7200000,"roi");t.put("amount",20);t.put("netProfit",195.8);
        assertEquals(false,review(result(List.of(t),195.8)).get("available"));
    }
    @Test void refusesMissingCandlesAndInvalidAssumptions() {
        String r=result(List.of(),0);
        assertEquals(false,HistoricalReview.calculate(r,1000,.001,start,start+10800000,candles().subList(0,2)).get("available"));
        assertEquals(false,HistoricalReview.calculate(r,Double.NaN,.001,start,start+10800000,candles()).get("available"));
        assertEquals(false,HistoricalReview.calculate(r,1000,.1,start,start+10800000,candles()).get("available"));
    }
}
