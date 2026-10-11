package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.RsiStrategyRequest;
import jakarta.validation.Validation;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;

public final class RsiStrategyTemplate {
    private static final String MARKER="# quant-rsi-config/v1 ";
    private static final jakarta.validation.Validator VALIDATOR=Validation.buildDefaultValidatorFactory().getValidator();
    private RsiStrategyTemplate() {}
    public static RsiStrategyRequest defaults() {
        return new RsiStrategyRequest(14,30,70,new BigDecimal("0.03"),new BigDecimal("0.06"));
    }
    public static Map<String,Object> configuration(RsiStrategyRequest request) {
        if(request==null || !VALIDATOR.validate(request).isEmpty() || request.entryThreshold()>=request.exitThreshold())
            throw new IllegalArgumentException("RSI周期须为2至50根，阈值须为1至99且买入线低于卖出线，风险比例须在允许范围内");
        return new TreeMap<>(Map.of("template","RSI_REVERSION","rsiPeriod",request.rsiPeriod(),"entryThreshold",request.entryThreshold(),"exitThreshold",request.exitThreshold(),
                "stopLossRatio",request.stopLossRatio().stripTrailingZeros(),"takeProfitRatio",request.takeProfitRatio().stripTrailingZeros()));
    }
    public static String render(RsiStrategyRequest request) throws java.io.IOException {
        var config=configuration(request);
        String body=new ClassPathResource("quant/QuantRsiReversion.py").getContentAsString(StandardCharsets.UTF_8)
                .replace("__PERIOD__",request.rsiPeriod().toString()).replace("__ENTRY__",request.entryThreshold().toString()).replace("__EXIT__",request.exitThreshold().toString())
                .replace("__ROI__",request.takeProfitRatio().stripTrailingZeros().toPlainString()).replace("__STOP__",request.stopLossRatio().stripTrailingZeros().toPlainString());
        return MARKER+JsonUtils.toJsonString(config)+"\n"+body;
    }
    public static Map<String,Object> readConfiguration(String source) {
        try {
            if(source==null || !source.startsWith(MARKER))return null;
            int end=source.indexOf('\n');if(end<0 || end>512)return null;
            var json=JsonUtils.getObjectMapper().readTree(source.substring(MARKER.length(),end));
            var request=new RsiStrategyRequest(json.path("rsiPeriod").intValue(),json.path("entryThreshold").intValue(),json.path("exitThreshold").intValue(),
                    json.path("stopLossRatio").decimalValue(),json.path("takeProfitRatio").decimalValue());
            return render(request).equals(source)?configuration(request):null;
        } catch(Exception ignored) { return null; }
    }
}
