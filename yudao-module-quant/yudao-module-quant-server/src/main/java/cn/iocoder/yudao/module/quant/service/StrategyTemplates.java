package cn.iocoder.yudao.module.quant.service;
import java.util.Map;
public final class StrategyTemplates {
    private StrategyTemplates(){}
    public static Map<String,Object> readConfiguration(String source){
        var ema=EmaStrategyTemplate.readConfiguration(source);
        if(ema!=null)return ema;
        var breakout=BreakoutStrategyTemplate.readConfiguration(source);
        if(breakout!=null)return breakout;
        var rsi=RsiStrategyTemplate.readConfiguration(source);
        return rsi!=null?rsi:BollingerRsiStrategyTemplate.readConfiguration(source);
    }
}
