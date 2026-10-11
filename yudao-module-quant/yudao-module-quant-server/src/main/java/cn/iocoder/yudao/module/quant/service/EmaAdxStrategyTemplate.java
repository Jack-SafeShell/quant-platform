package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.EmaAdxStrategyRequest;
import jakarta.validation.Validation;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;

public final class EmaAdxStrategyTemplate {
    private static final String MARKER="# quant-ema-adx-config/v1 ";
    private static final jakarta.validation.Validator VALIDATOR=Validation.buildDefaultValidatorFactory().getValidator();
    private EmaAdxStrategyTemplate() {}
    public static EmaAdxStrategyRequest defaults(){return new EmaAdxStrategyRequest(20,60,14,25,new BigDecimal("0.03"),new BigDecimal("0.06"));}
    public static Map<String,Object> configuration(EmaAdxStrategyRequest r){
        if(r==null || !VALIDATOR.validate(r).isEmpty() || r.fastPeriod()>=r.slowPeriod())throw new IllegalArgumentException("EMA短期2～119根且小于长期3～120根、ADX周期2～50根、最低强度10～60刻度，风险比例须在允许范围内");
        return new TreeMap<>(Map.of("template","EMA_ADX","fastPeriod",r.fastPeriod(),"slowPeriod",r.slowPeriod(),"adxPeriod",r.adxPeriod(),"adxThreshold",r.adxThreshold(),"stopLossRatio",r.stopLossRatio().stripTrailingZeros(),"takeProfitRatio",r.takeProfitRatio().stripTrailingZeros()));
    }
    public static String render(EmaAdxStrategyRequest r)throws java.io.IOException {
        var config=configuration(r);
        String body=new ClassPathResource("quant/QuantEmaAdx.py").getContentAsString(StandardCharsets.UTF_8)
                .replace("__FAST__",r.fastPeriod().toString()).replace("__SLOW__",r.slowPeriod().toString())
                .replace("__ADX_PERIOD__",r.adxPeriod().toString()).replace("__ADX_THRESHOLD__",r.adxThreshold().toString())
                .replace("__ROI__",r.takeProfitRatio().stripTrailingZeros().toPlainString()).replace("__STOP__",r.stopLossRatio().stripTrailingZeros().toPlainString());
        return MARKER+JsonUtils.toJsonString(config)+"\n"+body;
    }
    public static Map<String,Object> readConfiguration(String source){
        try {
            if(source==null || !source.startsWith(MARKER))return null;
            int end=source.indexOf('\n');if(end<0 || end>512)return null;
            var j=JsonUtils.getObjectMapper().readTree(source.substring(MARKER.length(),end));
            var r=new EmaAdxStrategyRequest(j.path("fastPeriod").intValue(),j.path("slowPeriod").intValue(),j.path("adxPeriod").intValue(),j.path("adxThreshold").intValue(),j.path("stopLossRatio").decimalValue(),j.path("takeProfitRatio").decimalValue());
            return render(r).equals(source)?configuration(r):null;
        }catch(Exception ignored){return null;}
    }
}
