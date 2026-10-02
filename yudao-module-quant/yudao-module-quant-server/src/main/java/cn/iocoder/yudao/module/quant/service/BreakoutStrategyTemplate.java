package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.BreakoutStrategyRequest;
import jakarta.validation.Validation;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;

public final class BreakoutStrategyTemplate {
    private static final String MARKER="# quant-breakout-config/v1 ";
    private static final jakarta.validation.Validator VALIDATOR=Validation.buildDefaultValidatorFactory().getValidator();
    private BreakoutStrategyTemplate(){}
    public static Map<String,Object> configuration(BreakoutStrategyRequest request){
        if(request==null||!VALIDATOR.validate(request).isEmpty())throw new IllegalArgumentException("Invalid breakout periods or risk ratios");
        return new TreeMap<>(Map.of("template","CHANNEL_BREAKOUT","entryPeriod",request.entryPeriod(),"exitPeriod",request.exitPeriod(),"stopLossRatio",request.stopLossRatio().stripTrailingZeros(),"takeProfitRatio",request.takeProfitRatio().stripTrailingZeros()));
    }
    public static String render(BreakoutStrategyRequest request)throws java.io.IOException{
        var config=configuration(request);
        String body=new ClassPathResource("quant/QuantChannelBreakout.py").getContentAsString(StandardCharsets.UTF_8)
                .replace("__ENTRY__",request.entryPeriod().toString()).replace("__EXIT__",request.exitPeriod().toString())
                .replace("__ROI__",request.takeProfitRatio().stripTrailingZeros().toPlainString()).replace("__STOP__",request.stopLossRatio().stripTrailingZeros().toPlainString());
        return MARKER+JsonUtils.toJsonString(config)+"\n"+body;
    }
    public static Map<String,Object> readConfiguration(String source){
        try{
            if(source==null||!source.startsWith(MARKER))return null;
            int end=source.indexOf('\n');if(end<0||end>512)return null;
            var json=JsonUtils.getObjectMapper().readTree(source.substring(MARKER.length(),end));
            var request=new BreakoutStrategyRequest(json.path("entryPeriod").intValue(),json.path("exitPeriod").intValue(),json.path("stopLossRatio").decimalValue(),json.path("takeProfitRatio").decimalValue());
            return render(request).equals(source)?configuration(request):null;
        }catch(Exception ignored){return null;}
    }
}
