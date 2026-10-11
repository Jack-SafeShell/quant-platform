package cn.iocoder.yudao.module.quant;

import cn.iocoder.yudao.module.quant.api.backtest.BollingerRsiStrategyRequest;
import cn.iocoder.yudao.module.quant.service.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BollingerRsiStrategyTemplateTest {
    private BollingerRsiStrategyRequest config(int band,String deviation,int rsi,int ceiling){return new BollingerRsiStrategyRequest(band,new BigDecimal(deviation),rsi,ceiling,new BigDecimal("0.03"),new BigDecimal("0.06"));}
    @Test void canonicalSourceAndDecimalsRejectTampering() throws Exception {
        var r=BollingerRsiStrategyTemplate.defaults();String source=BollingerRsiStrategyTemplate.render(r);
        assertEquals(BollingerRsiStrategyTemplate.configuration(r),StrategyTemplates.readConfiguration(source));
        assertEquals(source,BollingerRsiStrategyTemplate.render(config(20,"2.00",14,50)));
        assertNull(StrategyTemplates.readConfiguration(source.replace("matype=0","matype=1")));
        assertNull(StrategyTemplates.readConfiguration(source+"# changed"));
        assertNull(BollingerRsiStrategyTemplate.readConfiguration(source.substring(source.indexOf('\n')+1)));
        assertNull(RsiStrategyTemplate.readConfiguration(source));assertNull(EmaStrategyTemplate.readConfiguration(source));
    }
    @Test void invalidPeriodWidthCeilingAndRiskDoNotRender(){
        for(var r:new BollingerRsiStrategyRequest[]{null,config(4,"2",14,50),config(101,"2",14,50),config(20,"0.99",14,50),config(20,"4.01",14,50),config(20,"2.001",14,50),config(20,"2",1,50),config(20,"2",51,50),config(20,"2",14,9),config(20,"2",14,71),new BollingerRsiStrategyRequest(20,new BigDecimal("2"),14,50,new BigDecimal("0.0300001"),new BigDecimal("0.06"))})
            assertThrows(IllegalArgumentException.class,()->BollingerRsiStrategyTemplate.render(r));
    }
    @Test void neighboursChangeOnlyOneIntegerAndRespectBounds() throws Exception {
        String source=BollingerRsiStrategyTemplate.render(BollingerRsiStrategyTemplate.defaults());
        var variants=ParameterStabilityService.neighbours(source,"bandPeriod",2);
        assertEquals(java.util.List.of(18,20,22),variants.stream().map(v->v.get("value")).toList());
        for(var v:variants){var c=(Map<?,?>)v.get("configuration");assertEquals(new BigDecimal("2"),c.get("bandDeviation"));assertEquals(14,c.get("rsiPeriod"));assertEquals(50,c.get("rsiCeiling"));}
        assertThrows(IllegalArgumentException.class,()->ParameterStabilityService.neighbours(source,"bandDeviation",1));
        assertThrows(IllegalArgumentException.class,()->ParameterStabilityService.neighbours(BollingerRsiStrategyTemplate.render(config(5,"2",14,50)),"bandPeriod",1));
        assertThrows(IllegalArgumentException.class,()->ParameterStabilityService.neighbours(BollingerRsiStrategyTemplate.render(config(20,"2",14,70)),"rsiCeiling",1));
    }
}
