package cn.iocoder.yudao.module.quant.service;
import java.util.Map;
public final class StrategyTemplates {
    private StrategyTemplates(){}
    public static Map<String,Object> readConfiguration(String source){
        var ema=EmaStrategyTemplate.readConfiguration(source);
        return ema!=null?ema:BreakoutStrategyTemplate.readConfiguration(source);
    }
}
