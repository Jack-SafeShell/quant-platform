package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.BollingerRsiStrategyRequest;
import jakarta.validation.Validation;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;

public final class BollingerRsiStrategyTemplate {
    private static final String MARKER="# quant-bollinger-rsi-config/v1 ";
    private static final jakarta.validation.Validator VALIDATOR=Validation.buildDefaultValidatorFactory().getValidator();
    private BollingerRsiStrategyTemplate() {}
    public static BollingerRsiStrategyRequest defaults(){return new BollingerRsiStrategyRequest(20,new BigDecimal("2"),14,50,new BigDecimal("0.03"),new BigDecimal("0.06"));}
    public static Map<String,Object> configuration(BollingerRsiStrategyRequest r){
        if(r==null || !VALIDATOR.validate(r).isEmpty())throw new IllegalArgumentException("布林周期5～100根、宽度1～4倍、RSI周期2～50根、买入上限10～70，风险比例须在允许范围内");
        return new TreeMap<>(Map.of("template","BOLLINGER_RSI","bandPeriod",r.bandPeriod(),"bandDeviation",r.bandDeviation().stripTrailingZeros(),"rsiPeriod",r.rsiPeriod(),"rsiCeiling",r.rsiCeiling(),"stopLossRatio",r.stopLossRatio().stripTrailingZeros(),"takeProfitRatio",r.takeProfitRatio().stripTrailingZeros()));
    }
    public static String render(BollingerRsiStrategyRequest r)throws java.io.IOException {
        var config=configuration(r);
        String body=new ClassPathResource("quant/QuantBollingerRsi.py").getContentAsString(StandardCharsets.UTF_8)
                .replace("__BAND_PERIOD__",r.bandPeriod().toString()).replace("__DEVIATION__",r.bandDeviation().stripTrailingZeros().toPlainString())
                .replace("__RSI_PERIOD__",r.rsiPeriod().toString()).replace("__CEILING__",r.rsiCeiling().toString())
                .replace("__ROI__",r.takeProfitRatio().stripTrailingZeros().toPlainString()).replace("__STOP__",r.stopLossRatio().stripTrailingZeros().toPlainString());
        return MARKER+JsonUtils.toJsonString(config)+"\n"+body;
    }
    public static Map<String,Object> readConfiguration(String source){
        try {
            if(source==null || !source.startsWith(MARKER))return null;
            int end=source.indexOf('\n');if(end<0 || end>512)return null;
            var j=JsonUtils.getObjectMapper().readTree(source.substring(MARKER.length(),end));
            var r=new BollingerRsiStrategyRequest(j.path("bandPeriod").intValue(),j.path("bandDeviation").decimalValue(),j.path("rsiPeriod").intValue(),j.path("rsiCeiling").intValue(),j.path("stopLossRatio").decimalValue(),j.path("takeProfitRatio").decimalValue());
            return render(r).equals(source)?configuration(r):null;
        }catch(Exception ignored){return null;}
    }
}
