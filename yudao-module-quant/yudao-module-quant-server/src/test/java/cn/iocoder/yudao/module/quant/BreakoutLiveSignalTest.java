package cn.iocoder.yudao.module.quant;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.BreakoutStrategyRequest;
import cn.iocoder.yudao.module.quant.service.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BreakoutLiveSignalTest {
    private final Map<String,Object> config = BreakoutStrategyTemplate.configuration(
            new BreakoutStrategyRequest(2,2,new BigDecimal("0.02"),new BigDecimal("0.04")));
    private List<String> bar(int i, String high, String low, String close, String volume, boolean closed) {
        return List.of(String.valueOf(i * 3600000L), close, high, low, close, volume, "0", "0", closed ? "1" : "0");
    }
    private String json(List<List<String>> rows) { return JsonUtils.toJsonString(Map.of("code","0","data",rows)); }
    private List<List<String>> rows(String close, String volume) {
        return new ArrayList<>(List.of(bar(1,"10","8","9","1",true),bar(2,"11","9","10","1",true),
                bar(3,"99","6",close,volume,true),bar(4,"1000","1","1000","1",false)));
    }
    @Test void excludesCurrentAndUnclosedBarsAndHandlesReverseOrder() {
        var bars = rows("12","1"); Collections.reverse(bars);
        var signal = LiveAutomationService.evaluate(json(bars),config);
        assertEquals("BUY",signal.type()); assertEquals(10800000L,signal.candleAt());
        assertEquals(new BigDecimal("11"),signal.fast()); assertEquals(new BigDecimal("8"),signal.slow());
        assertEquals("CHANNEL_ENTRY",LiveAutomationService.signalReason(signal,config,null));
        assertEquals("HOLDING_POSITION",LiveAutomationService.signalReason(signal,config,new BigDecimal("12")));
        assertEquals("NONE",LiveAutomationService.positionExit(signal,config,new BigDecimal("12")).type());
        assertEquals("CHANNEL_ENTRY",LivePerformanceService.recordedReason("[CHANNEL_ENTRY] FILLED"));
    }
    @Test void strictBoundariesVolumeAndChannelExit() {
        assertEquals("NONE",LiveAutomationService.evaluate(json(rows("11","1")),config).type());
        assertEquals("NONE",LiveAutomationService.evaluate(json(rows("8","1")),config).type());
        assertEquals("NONE",LiveAutomationService.evaluate(json(rows("12","0")),config).type());
        var exit = LiveAutomationService.evaluate(json(rows("7","1")),config);
        assertEquals("SELL",exit.type()); assertEquals("CHANNEL_EXIT",LiveAutomationService.signalReason(exit,config,null));
        var hold = LiveAutomationService.evaluate(json(rows("10","1")),config);
        assertEquals("NO_BREAKOUT",LiveAutomationService.signalReason(hold,config,null));
        assertEquals("STOP_LOSS",LiveAutomationService.signalReason(hold,config,new BigDecimal("11")));
        assertEquals("SELL",LiveAutomationService.positionExit(hold,config,new BigDecimal("11")).type());
    }
    @Test void independentPeriodsAndBadEvidenceRejected() {
        var independent = BreakoutStrategyTemplate.configuration(new BreakoutStrategyRequest(2,3,new BigDecimal("0.02"),new BigDecimal("0.04")));
        var bars = List.of(bar(1,"100","5","10","1",true),bar(2,"11","9","10","1",true),
                bar(3,"12","8","10","1",true),bar(4,"13","6","6","1",true));
        var signal = LiveAutomationService.evaluate(json(bars),independent);
        assertEquals("NONE",signal.type()); assertEquals(new BigDecimal("12"),signal.fast()); assertEquals(new BigDecimal("5"),signal.slow());
        assertThrows(IllegalArgumentException.class,()->LiveAutomationService.evaluate(json(bars.subList(1,4)),independent));
        var duplicate = rows("12","1"); duplicate.add(duplicate.getFirst());
        assertThrows(IllegalArgumentException.class,()->LiveAutomationService.evaluate(json(duplicate),config));
        var invalid = rows("12","1"); invalid.set(0,bar(1,"8","10","9","1",true));
        assertThrows(IllegalArgumentException.class,()->LiveAutomationService.evaluate(json(invalid),config));
    }
}
