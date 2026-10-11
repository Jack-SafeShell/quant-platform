package cn.iocoder.yudao.module.quant;

import cn.iocoder.yudao.module.quant.api.backtest.RsiStrategyRequest;
import cn.iocoder.yudao.module.quant.service.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class RsiStrategyTemplateTest {
    private RsiStrategyRequest config(int period,int entry,int exit) {
        return new RsiStrategyRequest(period,entry,exit,new BigDecimal("0.03"),new BigDecimal("0.06"));
    }
    @Test void recognizesOnlyIntactCanonicalSource() throws Exception {
        var request=RsiStrategyTemplate.defaults();String source=RsiStrategyTemplate.render(request);
        assertEquals(RsiStrategyTemplate.configuration(request),StrategyTemplates.readConfiguration(source));
        assertEquals(source,RsiStrategyTemplate.render(new RsiStrategyRequest(14,30,70,new BigDecimal("0.030000"),new BigDecimal("0.060000"))));
        assertNull(StrategyTemplates.readConfiguration(source.replace("timeperiod=14","timeperiod=15")));
        assertNull(RsiStrategyTemplate.readConfiguration(source+"# changed"));
        assertNull(RsiStrategyTemplate.readConfiguration(source.substring(source.indexOf('\n')+1)));
        assertNull(EmaStrategyTemplate.readConfiguration(source));assertNull(BreakoutStrategyTemplate.readConfiguration(source));
    }
    @Test void rejectsInvalidThresholdsPeriodsAndRiskPrecision() {
        for(var request:new RsiStrategyRequest[]{config(1,30,70),config(51,30,70),config(14,0,70),config(14,30,100),config(14,70,30),config(14,30,30),null,
                new RsiStrategyRequest(null,30,70,new BigDecimal("0.03"),new BigDecimal("0.06")),
                new RsiStrategyRequest(14,30,70,new BigDecimal("0.0300001"),new BigDecimal("0.06"))})
            assertThrows(IllegalArgumentException.class,()->RsiStrategyTemplate.render(request));
    }
}
