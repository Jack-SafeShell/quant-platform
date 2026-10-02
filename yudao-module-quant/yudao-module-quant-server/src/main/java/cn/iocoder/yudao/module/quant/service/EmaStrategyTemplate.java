package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.EmaStrategyRequest;
import org.springframework.core.io.ClassPathResource;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/** Server-owned template. User input is limited to validated numbers, never Python. */
public final class EmaStrategyTemplate {
    private static final String MARKER = "# quant-ema-config/v1 ";
    private static final jakarta.validation.Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private EmaStrategyTemplate() { }
    public static EmaStrategyRequest defaults() {
        return new EmaStrategyRequest(20, 60, new BigDecimal("0.02"), new BigDecimal("0.04"));
    }
    private static void validate(EmaStrategyRequest request) {
        if (request == null) throw new IllegalArgumentException("策略配置不能为空");
        if (!VALIDATOR.validate(request).isEmpty())
            throw new IllegalArgumentException("EMA 周期或止损止盈参数超出允许范围，快周期须小于慢周期");
    }
    private static String decimal(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    public static Map<String, Object> configuration(EmaStrategyRequest request) {
        return new TreeMap<>(Map.of("fastPeriod", request.fastPeriod(), "slowPeriod", request.slowPeriod(),
                "stopLossRatio", new BigDecimal(decimal(request.stopLossRatio())),
                "takeProfitRatio", new BigDecimal(decimal(request.takeProfitRatio()))));
    }
    public static String baseline() throws java.io.IOException {
        return new ClassPathResource("quant/QuantEmaBaseline.py").getContentAsString(StandardCharsets.UTF_8);
    }
    public static String render(EmaStrategyRequest request) throws java.io.IOException {
        validate(request);
        var config = configuration(request);
        String baseline = baseline();
        if (config.equals(configuration(defaults()))) return baseline;
        String body = baseline.replace("timeperiod=20", "timeperiod=__FAST__")
                .replace("timeperiod=60", "timeperiod=__SLOW__")
                .replace("timeperiod=__FAST__", "timeperiod=" + request.fastPeriod())
                .replace("timeperiod=__SLOW__", "timeperiod=" + request.slowPeriod())
                .replace("minimal_roi = {\"0\": 0.04}", "minimal_roi = {\"0\": " + decimal(request.takeProfitRatio()) + "}")
                .replace("stoploss = -0.02", "stoploss = -" + decimal(request.stopLossRatio()))
                .replace("\"ema20\"", "\"ema_fast\"").replace("\"ema60\"", "\"ema_slow\"");
        return MARKER + JsonUtils.toJsonString(config) + "\n" + body;
    }
    /** Return configuration only for an exact known template; never guess legacy source parameters. */
    public static Map<String, Object> readConfiguration(String source) {
        try {
            if (baseline().equals(source)) return configuration(defaults());
            if (source == null || !source.startsWith(MARKER)) return null;
            int newline = source.indexOf('\n');
            if (newline < 0 || newline > 512) return null;
            var json = JsonUtils.getObjectMapper().readTree(source.substring(MARKER.length(), newline));
            var request = new EmaStrategyRequest(json.path("fastPeriod").intValue(), json.path("slowPeriod").intValue(),
                    json.path("stopLossRatio").decimalValue(), json.path("takeProfitRatio").decimalValue());
            return render(request).equals(source) ? configuration(request) : null;
        } catch (Exception ignored) { return null; }
    }
}
