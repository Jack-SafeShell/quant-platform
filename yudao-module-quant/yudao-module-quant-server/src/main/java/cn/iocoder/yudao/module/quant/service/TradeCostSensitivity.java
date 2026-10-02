package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import tools.jackson.databind.JsonNode;
import java.math.*;
import java.time.OffsetDateTime;
import java.util.*;

/** Fixed-trade cash-flow estimate, not an engine replay or candle equity curve. */
public final class TradeCostSensitivity {
    private TradeCostSensitivity() {}
    private record Trade(long closedAt, BigDecimal open, BigDecimal close, BigDecimal fee, BigDecimal profit) {}
    private static BigDecimal number(JsonNode n, String field) {
        var value = n.path(field);
        if (!value.isNumber() || !Double.isFinite(value.asDouble())) throw new IllegalArgumentException("Missing numeric " + field);
        return value.decimalValue();
    }
    public static Map<String, Object> calculate(String json, BigDecimal balance, int feeBps, int slippageBps) {
        if (feeBps < 0 || feeBps > 100 || slippageBps < 0 || slippageBps > 100 || balance == null || balance.signum() <= 0)
            throw new IllegalArgumentException("Invalid cost assumptions");
        try {
            var root = JsonUtils.getObjectMapper().readTree(json);
            if (!root.path("trades").isArray() || !root.path("totalTrades").isIntegralNumber()
                    || root.path("trades").size() != root.path("totalTrades").asInt()) throw new IllegalArgumentException("Incomplete trades");
            List<Trade> trades = new ArrayList<>(); BigDecimal total = BigDecimal.ZERO;
            for (var t : root.path("trades")) {
                if (!t.has("isShort") || !t.has("isOpen") || t.path("isShort").asBoolean() || t.path("isOpen").asBoolean())
                    throw new IllegalArgumentException("Only closed spot long trades supported");
                var amount = number(t, "amount"); var open = amount.multiply(number(t, "open_rate")); var close = amount.multiply(number(t, "close_rate"));
                var fo = number(t, "fee_open"); var fc = number(t, "fee_close"); var profit = number(t, "netProfit");
                if (amount.signum() <= 0 || open.signum() <= 0 || close.signum() <= 0 || fo.signum() < 0 || fc.signum() < 0 || fo.compareTo(BigDecimal.ONE) > 0 || fc.compareTo(BigDecimal.ONE) > 0)
                    throw new IllegalArgumentException("Invalid trade cash flows");
                var fee = open.multiply(fo).add(close.multiply(fc));
                if (close.subtract(open).subtract(fee).subtract(profit).abs().compareTo(new BigDecimal("0.00001")) > 0)
                    throw new IllegalArgumentException("Unsupported profit accounting");
                long timestamp = OffsetDateTime.parse(t.path("closedAt").asText().replace(' ', 'T')).toInstant().toEpochMilli();
                trades.add(new Trade(timestamp, open, close, fee, profit)); total = total.add(profit);
            }
            if (total.subtract(number(root, "netProfit")).abs().compareTo(new BigDecimal("0.00001")) > 0)
                throw new IllegalArgumentException("Trade profit does not match summary");
            trades.sort(Comparator.comparingLong(Trade::closedAt));
            BigDecimal feeRate = BigDecimal.valueOf(feeBps, 4), slip = BigDecimal.valueOf(slippageBps, 4);
            BigDecimal fees = BigDecimal.ZERO, slipCost = BigDecimal.ZERO, adjusted = BigDecimal.ZERO;
            var events = new TreeMap<Long, BigDecimal>();
            for (var t : trades) {
                var adverseOpen = t.open.multiply(BigDecimal.ONE.add(slip)); var adverseClose = t.close.multiply(BigDecimal.ONE.subtract(slip));
                var scenarioFee = adverseOpen.add(adverseClose).multiply(feeRate);
                var drag = t.open.add(t.close).multiply(slip);
                var pnl = t.profit.add(t.fee).subtract(scenarioFee).subtract(drag);
                fees = fees.add(scenarioFee); slipCost = slipCost.add(drag); adjusted = adjusted.add(pnl);
                events.merge(t.closedAt, pnl, BigDecimal::add);
            }
            var equity = balance; var peak = balance; BigDecimal drawdown = BigDecimal.ZERO;
            for (var pnl : events.values()) {
                equity = equity.add(pnl); peak = peak.max(equity);
                drawdown = drawdown.max(peak.subtract(equity).divide(peak, 12, RoundingMode.HALF_UP));
            }
            var result = new LinkedHashMap<String, Object>();
            result.put("available", true); result.put("tradeCount", trades.size()); result.put("baseNetProfit", total);
            result.put("adjustedNetProfit", adjusted); result.put("adjustedReturn", adjusted.divide(balance, 12, RoundingMode.HALF_UP));
            result.put("estimatedFees", fees); result.put("estimatedSlippageCost", slipCost); result.put("costImpact", total.subtract(adjusted));
            result.put("realizedDrawdown", drawdown); result.put("feeBps", feeBps); result.put("slippageBps", slippageBps);
            return result;
        } catch (Exception e) {
            return Map.of("available", false, "reason", "MISSING_OR_UNSUPPORTED_TRADE_EVIDENCE");
        }
    }
}
