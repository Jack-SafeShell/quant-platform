package cn.iocoder.yudao.module.quant;

import cn.iocoder.yudao.module.quant.api.backtest.BreakoutStrategyRequest;
import cn.iocoder.yudao.module.quant.service.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class BreakoutStrategyTemplateTest {
    private BreakoutStrategyRequest config(int entry, int exit) {
        return new BreakoutStrategyRequest(entry, exit, new BigDecimal("0.03"), new BigDecimal("0.06"));
    }
    @Test void recognizesOnlyCompleteImmutableTemplate() throws Exception {
        String source = BreakoutStrategyTemplate.render(config(20, 10));
        assertEquals(BreakoutStrategyTemplate.configuration(config(20, 10)), StrategyTemplates.readConfiguration(source));
        assertEquals(source, BreakoutStrategyTemplate.render(new BreakoutStrategyRequest(20, 10, new BigDecimal("0.030000"), new BigDecimal("0.060000"))));
        assertNull(BreakoutStrategyTemplate.readConfiguration(source + "# changed"));
        assertNull(BreakoutStrategyTemplate.readConfiguration(source.replace("rolling(20)", "rolling(21)")));
        assertNull(EmaStrategyTemplate.readConfiguration(source));
        assertEquals(EmaStrategyTemplate.configuration(EmaStrategyTemplate.defaults()), StrategyTemplates.readConfiguration(EmaStrategyTemplate.baseline()));
    }
    @Test void rejectsMissingOutOfRangeAndExcessPrecision() {
        assertThrows(IllegalArgumentException.class, () -> BreakoutStrategyTemplate.render(config(1, 10)));
        assertThrows(IllegalArgumentException.class, () -> BreakoutStrategyTemplate.render(config(20, 121)));
        assertThrows(IllegalArgumentException.class, () -> BreakoutStrategyTemplate.render(null));
        assertThrows(IllegalArgumentException.class, () -> BreakoutStrategyTemplate.render(new BreakoutStrategyRequest(20, 10, new BigDecimal("0.0300001"), new BigDecimal("0.06"))));
    }
}
