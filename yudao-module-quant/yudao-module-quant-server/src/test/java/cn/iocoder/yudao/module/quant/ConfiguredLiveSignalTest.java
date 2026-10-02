package cn.iocoder.yudao.module.quant;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.EmaStrategyRequest;
import cn.iocoder.yudao.module.quant.service.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfiguredLiveSignalTest {
    private final Map<String,Object> config=EmaStrategyTemplate.configuration(new EmaStrategyRequest(2,3,new BigDecimal("0.02"),new BigDecimal("0.04")));
    @Test void configuredPeriodsUseOnlyClosedCandles(){
        var rows=new ArrayList<List<String>>();int i=0;
        for(String close:List.of("100","100","100","90","130"))rows.add(List.of(String.valueOf(++i),"0","0","0",close,"1","0","0","1"));
        rows.add(List.of("6","0","0","0","1","1","0","0","0"));
        String json=JsonUtils.toJsonString(Map.of("code","0","data",rows));
        var signal=LiveAutomationService.evaluate(json,config);
        assertEquals("BUY",signal.type());assertEquals(5,signal.candleAt());
        assertThrows(IllegalArgumentException.class,()->LiveAutomationService.evaluate(json));
    }
    @Test void exitsUseSessionEntryAndBoundaryPrices(){
        for(String close:List.of("98","104"))assertEquals("SELL",LiveAutomationService.positionExit(new LiveAutomationService.MarketSignal(1,"NONE",new BigDecimal(close),BigDecimal.ONE,BigDecimal.ONE),config,new BigDecimal("100")).type());
        var buy=new LiveAutomationService.MarketSignal(1,"BUY",new BigDecimal("100"),BigDecimal.ONE,BigDecimal.ONE);
        assertEquals("NONE",LiveAutomationService.positionExit(buy,config,new BigDecimal("100")).type());
        assertEquals("BUY",LiveAutomationService.positionExit(buy,config,null).type());
    }
    @Test void triggerReasonsAreExplicitAndLegacyMessagesAreNotGuessed(){
        var stop=new LiveAutomationService.MarketSignal(1,"NONE",new BigDecimal("98"),BigDecimal.ONE,BigDecimal.ONE);
        assertEquals("STOP_LOSS",LiveAutomationService.signalReason(stop,config,new BigDecimal("100")));
        var roi=new LiveAutomationService.MarketSignal(1,"NONE",new BigDecimal("104"),BigDecimal.ONE,BigDecimal.ONE);
        assertEquals("TAKE_PROFIT",LiveAutomationService.signalReason(roi,config,new BigDecimal("100")));
        assertEquals("NO_CROSS",LiveAutomationService.signalReason(roi,config,null));
        assertEquals("STOP_LOSS",LivePerformanceService.recordedReason("[STOP_LOSS] FILLED"));
        assertEquals("UNRECORDED",LivePerformanceService.recordedReason("FILLED"));
        assertEquals("UNRECORDED",LivePerformanceService.recordedReason("[UNTRUSTED] FILLED"));
    }
    @Test void entryCostSurvivesPartialSalesAndResetsAfterFlat(){
        var buy=Map.<String,Object>of("side","BUY","amount","2","price","100");
        var sell=Map.<String,Object>of("side","SELL","amount","1","price","150");
        assertEquals(0,new BigDecimal("100").compareTo(LiveAutomationService.entryPrice(List.of(buy,sell))));
        assertNull(LiveAutomationService.entryPrice(List.of(buy,sell,sell)));
        assertEquals(0,new BigDecimal("200").compareTo(LiveAutomationService.entryPrice(List.of(buy,sell,sell,Map.of("side","BUY","amount","1","price","200")))));
        assertThrows(IllegalStateException.class,()->LiveAutomationService.entryPrice(List.of(sell)));
        assertThrows(IllegalStateException.class,()->LiveAutomationService.entryPrice(List.of(Map.of("side","BUY","amount","1","price","0"))));
    }
}
