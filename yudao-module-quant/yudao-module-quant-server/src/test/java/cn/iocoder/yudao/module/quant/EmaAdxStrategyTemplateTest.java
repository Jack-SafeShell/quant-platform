package cn.iocoder.yudao.module.quant;
import cn.iocoder.yudao.module.quant.api.backtest.EmaAdxStrategyRequest;
import cn.iocoder.yudao.module.quant.service.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class EmaAdxStrategyTemplateTest {
 private EmaAdxStrategyRequest c(int f,int s,int p,int t){return new EmaAdxStrategyRequest(f,s,p,t,new BigDecimal("0.03"),new BigDecimal("0.06"));}
 @Test void canonicalReadRequiresExactUnchangedSource()throws Exception{
  String source=EmaAdxStrategyTemplate.render(c(20,60,14,25));
  assertEquals(EmaAdxStrategyTemplate.configuration(c(20,60,14,25)),StrategyTemplates.readConfiguration(source));
  assertEquals(source,EmaAdxStrategyTemplate.render(new EmaAdxStrategyRequest(20,60,14,25,new BigDecimal("0.030000"),new BigDecimal("0.060000"))));
  assertNull(StrategyTemplates.readConfiguration(source.replace(" >= 25", " > 25")));
  assertNull(StrategyTemplates.readConfiguration(source+"# changed"));assertNull(EmaStrategyTemplate.readConfiguration(source));
 }
 @Test void invalidCrossRelationPeriodsThresholdsAndRiskReject(){
  for(var v:new EmaAdxStrategyRequest[]{null,c(1,60,14,25),c(20,121,14,25),c(60,60,14,25),c(61,60,14,25),c(20,60,1,25),c(20,60,51,25),c(20,60,14,9),c(20,60,14,61),new EmaAdxStrategyRequest(20,60,14,25,new BigDecimal("0.0300001"),new BigDecimal("0.06"))})assertThrows(IllegalArgumentException.class,()->EmaAdxStrategyTemplate.render(v));
 }
 @Test void neighboursKeepRiskAndEmaAndRejectClipping()throws Exception{
  String source=EmaAdxStrategyTemplate.render(EmaAdxStrategyTemplate.defaults());var v=ParameterStabilityService.neighbours(source,"adxThreshold",2);
  assertEquals(List.of(23,25,27),v.stream().map(x->x.get("value")).toList());
  for(var x:v){var c=(Map<?,?>)x.get("configuration");assertEquals(20,c.get("fastPeriod"));assertEquals(60,c.get("slowPeriod"));assertEquals(14,c.get("adxPeriod"));assertEquals(new BigDecimal("0.03"),c.get("stopLossRatio"));}
  assertThrows(IllegalArgumentException.class,()->ParameterStabilityService.neighbours(source,"rsiPeriod",1));
  assertThrows(IllegalArgumentException.class,()->ParameterStabilityService.neighbours(EmaAdxStrategyTemplate.render(c(20,60,14,10)),"adxThreshold",1));
  assertThrows(IllegalArgumentException.class,()->ParameterStabilityService.neighbours(EmaAdxStrategyTemplate.render(c(59,60,14,25)),"fastPeriod",1));
 }
}
