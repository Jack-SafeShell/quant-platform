package cn.iocoder.yudao.module.quant;

import cn.iocoder.yudao.module.quant.api.backtest.EmaStrategyRequest;
import cn.iocoder.yudao.module.quant.service.EmaStrategyTemplate;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class EmaStrategyTemplateTest {
    @Test void preservesBaselineAndRendersIndependentPeriods() throws Exception {
        assertEquals(EmaStrategyTemplate.baseline(), EmaStrategyTemplate.render(EmaStrategyTemplate.defaults()));
        var config = new EmaStrategyRequest(60, 100, new BigDecimal("0.015000"), new BigDecimal("0.030000"));
        var source = EmaStrategyTemplate.render(config);
        assertTrue(source.contains("dataframe[\"ema_fast\"] = ta.EMA(dataframe, timeperiod=60)"));
        assertTrue(source.contains("dataframe[\"ema_slow\"] = ta.EMA(dataframe, timeperiod=100)"));
        assertTrue(source.contains("stoploss = -0.015"));
        assertTrue(source.contains("minimal_roi = {\"0\": 0.03}"));
        assertEquals(EmaStrategyTemplate.configuration(config), EmaStrategyTemplate.readConfiguration(source));
        assertNull(EmaStrategyTemplate.readConfiguration(source.replace("stoploss = -0.015", "stoploss = -0.1")));
    }
    @Test void rejectsInvalidOrExcessiveParametersWithoutGeneratingSource() {
        assertThrows(IllegalArgumentException.class, () -> EmaStrategyTemplate.render(new EmaStrategyRequest(60, 20, new BigDecimal("0.02"), new BigDecimal("0.04"))));
        assertThrows(IllegalArgumentException.class, () -> EmaStrategyTemplate.render(new EmaStrategyRequest(20, 121, new BigDecimal("0.02"), new BigDecimal("0.04"))));
        assertThrows(IllegalArgumentException.class, () -> EmaStrategyTemplate.render(new EmaStrategyRequest(20, 60, new BigDecimal("-0.02"), new BigDecimal("0.04"))));
        assertThrows(IllegalArgumentException.class, () -> EmaStrategyTemplate.render(new EmaStrategyRequest(20, 60, new BigDecimal("0.0200001"), new BigDecimal("0.04"))));
        assertThrows(IllegalArgumentException.class, () -> EmaStrategyTemplate.render(new EmaStrategyRequest(null, 60, new BigDecimal("0.02"), new BigDecimal("0.04"))));
    }
}
