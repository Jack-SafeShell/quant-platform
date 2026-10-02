package cn.iocoder.yudao.module.quant;

import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import cn.iocoder.yudao.module.quant.service.LiveRunBudget;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class LiveRunBudgetTest {
    private final QuantProperties p=new QuantProperties();
    @Test void defaultsAndSmallerBudgetsPreserveCeilings() {
        assertEquals(p.getLiveAutomationOrderNotional(),LiveRunBudget.resolve(p,BigDecimal.TEN,null,null,null,null).orderNotional());
        var budget=LiveRunBudget.resolve(p,new BigDecimal("4"),new BigDecimal("3"),new BigDecimal("2"),20,10);
        assertEquals(new BigDecimal("3"),budget.orderNotional());assertEquals(20,budget.feeBps());
    }
    @Test void rejectsPolicyOrSystemCeilingAndInvalidAssumptions() {
        assertThrows(IllegalArgumentException.class,()->LiveRunBudget.resolve(p,new BigDecimal("4"),new BigDecimal("5"),null,null,null));
        assertThrows(IllegalArgumentException.class,()->LiveRunBudget.resolve(p,new BigDecimal("20"),new BigDecimal("11"),null,null,null));
        assertThrows(IllegalArgumentException.class,()->LiveRunBudget.resolve(p,BigDecimal.TEN,null,new BigDecimal("6"),null,null));
        assertThrows(IllegalArgumentException.class,()->LiveRunBudget.resolve(p,BigDecimal.TEN,BigDecimal.ZERO,null,null,null));
        assertThrows(IllegalArgumentException.class,()->LiveRunBudget.resolve(p,BigDecimal.TEN,null,null,101,5));
        assertThrows(IllegalArgumentException.class,()->LiveRunBudget.resolve(p,BigDecimal.TEN,new BigDecimal("1.123456789"),null,10,5));
    }
}
