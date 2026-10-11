package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.BreakoutAtrStrategyRequest;
import jakarta.validation.Validation;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;

public final class BreakoutAtrStrategyTemplate {
    private static final String MARKER="# quant-breakout-atr-config/v1 ";
    private static final jakarta.validation.Validator VALIDATOR=Validation.buildDefaultValidatorFactory().getValidator();
    private BreakoutAtrStrategyTemplate() {}
    public static BreakoutAtrStrategyRequest defaults(){return new BreakoutAtrStrategyRequest(20,10,14,new BigDecimal("2"),new BigDecimal("0.03"),new BigDecimal("0.06"));}
    public static Map<String,Object> configuration(BreakoutAtrStrategyRequest r){
        if(r==null || !VALIDATOR.validate(r).isEmpty())throw new IllegalArgumentException("突破周期2～120根、ATR周期2～50根、ATR距离1～5倍（最多两位小数），风险比例须在允许范围内");
        return new TreeMap<>(Map.of("template","BREAKOUT_ATR","entryPeriod",r.entryPeriod(),"exitPeriod",r.exitPeriod(),"atrPeriod",r.atrPeriod(),"atrMultiplier",r.atrMultiplier().stripTrailingZeros(),"stopLossRatio",r.stopLossRatio().stripTrailingZeros(),"takeProfitRatio",r.takeProfitRatio().stripTrailingZeros()));
    }
    public static String render(BreakoutAtrStrategyRequest r)throws java.io.IOException {
        var config=configuration(r);
        String body=new ClassPathResource("quant/QuantBreakoutAtr.py").getContentAsString(StandardCharsets.UTF_8)
                .replace("__ENTRY__",r.entryPeriod().toString()).replace("__EXIT__",r.exitPeriod().toString())
                .replace("__ATR_PERIOD__",r.atrPeriod().toString()).replace("__ATR_MULTIPLIER__",r.atrMultiplier().stripTrailingZeros().toPlainString())
                .replace("__ROI__",r.takeProfitRatio().stripTrailingZeros().toPlainString()).replace("__STOP__",r.stopLossRatio().stripTrailingZeros().toPlainString());
        return MARKER+JsonUtils.toJsonString(config)+"\n"+body;
    }
    public static Map<String,Object> readConfiguration(String source){
        try {
            if(source==null || !source.startsWith(MARKER))return null;
            int end=source.indexOf('\n');if(end<0 || end>512)return null;
            var j=JsonUtils.getObjectMapper().readTree(source.substring(MARKER.length(),end));
            var r=new BreakoutAtrStrategyRequest(j.path("entryPeriod").intValue(),j.path("exitPeriod").intValue(),j.path("atrPeriod").intValue(),j.path("atrMultiplier").decimalValue(),j.path("stopLossRatio").decimalValue(),j.path("takeProfitRatio").decimalValue());
            return render(r).equals(source)?configuration(r):null;
        }catch(Exception ignored){return null;}
    }
}
