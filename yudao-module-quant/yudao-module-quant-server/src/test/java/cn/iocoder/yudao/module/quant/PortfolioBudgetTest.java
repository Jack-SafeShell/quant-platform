package cn.iocoder.yudao.module.quant;

import cn.iocoder.yudao.module.quant.api.backtest.PortfolioBudgetRequest;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import cn.iocoder.yudao.module.quant.service.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PortfolioBudgetTest {
    final QuantProperties properties=new QuantProperties();
    PortfolioBudgetRequest.Allocation row(String id,String capital,String order,String loss,String daily){return new PortfolioBudgetRequest.Allocation(id,new BigDecimal(capital),new BigDecimal(order),new BigDecimal(loss),new BigDecimal(daily));}
    PortfolioBudgetRequest request(String capital,PortfolioBudgetRequest.Allocation... rows){return new PortfolioBudgetRequest(new BigDecimal(capital),10,5,List.of(rows));}
    @Test void twoPlansShareSystemLimitsWithoutIncreasingThem(){
        var totals=PortfolioBudgetPlan.calculate(properties,request("20",row("ema","8","5","2","10"),row("breakout","10","5","2","10")));
        assertEquals(new BigDecimal("18"),totals.get("allocatedCapital"));assertEquals(new BigDecimal("2"),totals.get("unallocatedCapital"));
        assertEquals(new BigDecimal("4"),totals.get("combinedLossBudget"));assertEquals(new BigDecimal("20"),totals.get("combinedDailyNotional"));
    }
    @Test void aggregateLimitsCannotBeBypassedBySplittingStrategies(){
        for(var request:List.of(
                request("20",row("a","11","5","2","10"),row("b","10","5","2","10")),
                request("20",row("a","10","5","3","10"),row("b","10","5","3","10")),
                request("20",row("a","10","5","2","11"),row("b","10","5","2","10")),
                request("21",row("a","10","5","2","10"))))
            assertThrows(IllegalArgumentException.class,()->PortfolioBudgetPlan.calculate(properties,request));
    }
    @Test void duplicatesPrecisionAndUnfundedOrdersAreRejected(){
        for(var request:List.of(request("20",row("a","10","5","2","10"),row("a","10","5","2","10")),
                request("20",row("a","4","5","2","10")),request("20",row("a","10","5","2","4")),
                request("20",row("a","10.000000001","5","2","10")),request("20",row("a","1","1","2","10"))))
            assertThrows(IllegalArgumentException.class,()->PortfolioBudgetPlan.calculate(properties,request));
    }
    LiveControlService service(){return new LiveControlService(null,null,properties,null){
        public Map<String,Object> runPlan(long tenant,long owner,String report,BigDecimal order,BigDecimal loss,Integer fee,Integer slip){
            if(tenant!=1||owner!=10)throw new IllegalArgumentException("Foreign report");
            return Map.of("reportId",report,"strategy",Map.of("strategyVersionId",report.startsWith("same")?"shared":report),
                    "limits",Map.of("maxTotalExposure",20,"maxDailyNotional",report.equals("small")?4:20),"readyForStartRequest",true);
        }
    };}
    @Test void individuallyReadyPlansNeverStartOrReservePortfolioFunds(){
        var result=service().portfolioBudget(1,10,request("20",row("ema","10","5","2","10"),row("breakout","10","5","2","10")));
        assertEquals(true,result.get("budgetValid"));assertEquals(true,result.get("sharedInstrument"));
        assertEquals(false,result.get("readyForPortfolioStart"));assertEquals(false,result.get("fundsReserved"));
        assertEquals(false,result.get("multiStrategyExecutionSupported"));assertEquals(64,String.valueOf(result.get("evidenceHash")).length());
    }
    @Test void versionsBoundPolicyLimitsAndReportOwnershipRemainEnforced(){
        var service=service();var duplicate=request("20",row("same1","10","5","2","10"),row("same2","10","5","2","10"));
        assertThrows(IllegalArgumentException.class,()->service.portfolioBudget(1,10,duplicate));
        assertThrows(IllegalArgumentException.class,()->service.portfolioBudget(1,20,request("20",row("ema","10","5","2","10"))));
        assertThrows(IllegalArgumentException.class,()->service.portfolioBudget(1,10,request("20",row("small","10","5","2","10"))));
    }
}
