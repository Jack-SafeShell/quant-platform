package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import tools.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;

/** Hour-close liquidation-value estimate. It is deliberately distinct from engine intrabar drawdown. */
public final class HistoricalReview {
    private HistoricalReview() {}
    public record Trade(int number, long openedAt, long closedAt, double amount, double openPrice,
                        double closePrice, double openFee, double closeFee, double netProfit, String exitReason) {}
    public record Point(long time, double equity, double buyHold, double drawdownRatio) {}
    public record Month(String month, double realizedNetProfit, double realizedReturnRatio, int trades) {}
    private static double number(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isNumber() || !Double.isFinite(value.asDouble())) throw new IllegalArgumentException("Missing evidence");
        return value.asDouble();
    }
    private static long timestamp(JsonNode node, String field) {
        return OffsetDateTime.parse(node.path(field).asText().replace(' ', 'T')).toInstant().toEpochMilli();
    }
    public static Map<String, Object> calculate(String json, double balance, double fee, long start, long end,
                                               List<List<Number>> candles) {
        try {
            if (!Double.isFinite(balance) || balance <= 0 || !Double.isFinite(fee) || fee < 0 || fee > .01
                    || start >= end || candles.isEmpty() || candles.size() > 8784) throw new IllegalArgumentException();
            var root = JsonUtils.getObjectMapper().readTree(json);
            if (!root.path("trades").isArray() || !root.path("totalTrades").isIntegralNumber()
                    || root.path("totalTrades").asInt() != root.path("trades").size()) throw new IllegalArgumentException();
            List<Trade> trades = new ArrayList<>();
            for (var t : root.path("trades")) {
                String exit = t.path("exitReason").asText();
                // Freqtrade marks a final force_exit as is_open even though it provides a closing fill.
                if (!"BTC/USDT".equals(t.path("instrument").asText()) || !t.path("isShort").isBoolean()
                        || t.path("isShort").asBoolean() || !t.path("isOpen").isBoolean()
                        || (t.path("isOpen").asBoolean() && !"force_exit".equals(exit))) throw new IllegalArgumentException();
                long opened = timestamp(t, "openedAt"), closed = timestamp(t, "closedAt");
                double amount = number(t, "amount"), open = number(t, "open_rate"), close = number(t, "close_rate"),
                        fo = number(t, "fee_open"), fc = number(t, "fee_close"), profit = number(t, "netProfit");
                double calculated = amount * (close * (1 - fc) - open * (1 + fo));
                if (opened < start || opened >= end || closed < opened || closed > end || amount <= 0 || open <= 0 || close <= 0
                        || fo < 0 || fc < 0 || fo > .01 || fc > .01
                        || !Double.isFinite(calculated) || Math.abs(calculated - profit) > .00001)
                    throw new IllegalArgumentException();
                trades.add(new Trade(0, opened, closed, amount, open, close, fo, fc, profit, exit));
            }
            trades.sort(Comparator.comparingLong(Trade::openedAt).thenComparingLong(Trade::closedAt));
            long previousClose = start;
            List<Trade> ordered = new ArrayList<>();
            double total = 0;
            for (var t : trades) {
                if (t.openedAt < previousClose || !Double.isFinite(t.amount*t.openPrice*(1+t.openFee))
                        || t.amount*t.openPrice*(1+t.openFee)>balance+total+.00001) throw new IllegalArgumentException();
                previousClose = t.closedAt;
                total += t.netProfit;
                ordered.add(new Trade(ordered.size()+1, t.openedAt, t.closedAt, t.amount, t.openPrice, t.closePrice,
                        t.openFee, t.closeFee, t.netProfit, t.exitReason));
            }
            if (Math.abs(total - number(root, "netProfit")) > .00001
                    || Math.abs(total / balance - number(root, "returnRatio")) > .00001) throw new IllegalArgumentException();
            long expected = start;
            for (var c : candles) {
                if (c.size() != 6 || c.getFirst().longValue() != expected) throw new IllegalArgumentException();
                for (int i=1; i<6; i++) if (!Double.isFinite(c.get(i).doubleValue()) || c.get(i).doubleValue()<0
                        || (i<5 && c.get(i).doubleValue()==0)) throw new IllegalArgumentException();
                if (c.get(2).doubleValue()<Math.max(c.get(1).doubleValue(),c.get(4).doubleValue())
                        || c.get(3).doubleValue()>Math.min(c.get(1).doubleValue(),c.get(4).doubleValue())) throw new IllegalArgumentException();
                expected += 3600000;
            }
            if (expected != end) throw new IllegalArgumentException();
            double firstPrice = candles.getFirst().get(1).doubleValue();
            double heldAmount = balance / (firstPrice * (1+fee));
            List<Point> points = new ArrayList<>();
            points.add(new Point(start, balance, balance, 0));
            int next = 0; double realized = 0, peak = balance, maxDrawdown = 0;
            for (var candle : candles) {
                long time = candle.getFirst().longValue()+3600000;
                while (next < ordered.size() && ordered.get(next).closedAt <= time) realized += ordered.get(next++).netProfit;
                double equity = balance + realized;
                if (next < ordered.size()) {
                    var active = ordered.get(next);
                    if (active.openedAt < time) equity += active.amount * (candle.get(4).doubleValue() * (1-active.closeFee)
                            - active.openPrice * (1+active.openFee));
                }
                peak = Math.max(peak, equity);
                double dd = (peak-equity)/peak; maxDrawdown = Math.max(maxDrawdown, dd);
                points.add(new Point(time, equity, heldAmount*candle.get(4).doubleValue()*(1-fee), dd));
            }
            if (next != ordered.size() || Math.abs(points.getLast().equity - balance - total) > .00001) throw new IllegalArgumentException();
            var months = new TreeMap<String, List<Trade>>();
            for (var c : candles) months.computeIfAbsent(Instant.ofEpochMilli(c.getFirst().longValue()).atOffset(ZoneOffset.UTC).toString().substring(0,7), k -> new ArrayList<>());
            for (var t : ordered) {
                String month = Instant.ofEpochMilli(t.closedAt).atOffset(ZoneOffset.UTC).toString().substring(0,7);
                months.computeIfAbsent(month,k->new ArrayList<>()).add(t);
            }
            List<Month> monthly = new ArrayList<>();
            for (var entry : months.entrySet()) {
                double pnl=entry.getValue().stream().mapToDouble(Trade::netProfit).sum();
                monthly.add(new Month(entry.getKey(),pnl,pnl/balance,entry.getValue().size()));
            }
            var last = points.getLast();
            var summary = Map.of("startingBalance",balance,"netProfit",total,"finalEquity",last.equity,
                    "buyHoldNetProfit",last.buyHold-balance,"buyHoldReturnRatio",(last.buyHold-balance)/balance,
                    "excessReturnRatio",total/balance-(last.buyHold-balance)/balance,
                    "hourCloseMaxDrawdownRatio",maxDrawdown,"fee",fee);
            return Map.of("available",true,"schemaVersion","historical-review/v1","valuation","HOUR_CLOSE_ESTIMATED_LIQUIDATION",
                    "candles",candles,"points",points,"trades",ordered,"months",monthly,"summary",summary);
        } catch (Exception e) { return BacktestReviewService.unavailable("MISSING_OR_UNSUPPORTED_TRADE_EVIDENCE"); }
    }
}
