package cn.iocoder.yudao.module.quant;
import cn.iocoder.yudao.module.quant.api.backtest.BreakoutAtrStrategyRequest;
import cn.iocoder.yudao.module.quant.service.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class BreakoutAtrStrategyTemplateTest {
 private BreakoutAtrStrategyRequest c(int e,int x,int p,String m){return new BreakoutAtrStrategyRequest(e,x,p,new BigDecimal(m),new BigDecimal("0.03"),new BigDecimal("0.06"));}
 @Test void canonicalSourceAndDecimalMultipliersRejectTampering()throws Exception{
  String source=BreakoutAtrStrategyTemplate.render(c(20,10,14,"2"));
  assertEquals(BreakoutAtrStrategyTemplate.configuration(c(20,10,14,"2")),StrategyTemplates.readConfiguration(source));
  assertEquals(source,BreakoutAtrStrategyTemplate.render(c(20,10,14,"2.00")));
  assertNull(StrategyTemplates.readConfiguration(source.replace("trailing_stop = False","trailing_stop = True")));
  assertNull(StrategyTemplates.readConfiguration(source+"# changed"));assertNull(BreakoutStrategyTemplate.readConfiguration(source));
 }
 @Test void invalidPeriodsMultipliersAndRiskReject(){
  for(var v:new BreakoutAtrStrategyRequest[]{null,c(1,10,14,"2"),c(20,121,14,"2"),c(20,10,1,"2"),c(20,10,51,"2"),c(20,10,14,"0.99"),c(20,10,14,"5.01"),c(20,10,14,"2.001"),new BreakoutAtrStrategyRequest(20,10,14,new BigDecimal("2"),new BigDecimal("0.0300001"),new BigDecimal("0.06"))})assertThrows(IllegalArgumentException.class,()->BreakoutAtrStrategyTemplate.render(v));
 }
 @Test void neighboursKeepMultiplierAndRiskAndRejectClipping()throws Exception{
  String source=BreakoutAtrStrategyTemplate.render(BreakoutAtrStrategyTemplate.defaults());var v=ParameterStabilityService.neighbours(source,"atrPeriod",2);
  assertEquals(List.of(12,14,16),v.stream().map(x->x.get("value")).toList());
  for(var x:v){var c=(Map<?,?>)x.get("configuration");assertEquals(20,c.get("entryPeriod"));assertEquals(10,c.get("exitPeriod"));assertEquals(new BigDecimal("2"),c.get("atrMultiplier"));assertEquals(new BigDecimal("0.03"),c.get("stopLossRatio"));}
  assertThrows(IllegalArgumentException.class,()->ParameterStabilityService.neighbours(source,"atrMultiplier",1));
  assertThrows(IllegalArgumentException.class,()->ParameterStabilityService.neighbours(BreakoutAtrStrategyTemplate.render(c(20,10,2,"2")),"atrPeriod",1));
 }
}
