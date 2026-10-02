package cn.iocoder.yudao.module.quant;

import cn.iocoder.yudao.module.quant.service.TradeCostSensitivity;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class TradeCostSensitivityTest {
    private static final String TRADE = """
        {"totalTrades":1,"netProfit":9.79,"trades":[{"amount":1,"open_rate":100,"close_rate":110,
        "fee_open":0.001,"fee_close":0.001,"netProfit":9.79,"closedAt":"2026-04-01 01:00:00+00:00",
        "isShort":false,"isOpen":false}]}
        """;
    private BigDecimal value(String json, int fee, int slip, String field) {
        var result = TradeCostSensitivity.calculate(json, new BigDecimal("1000"), fee, slip);
        assertEquals(true, result.get("available")); return (BigDecimal) result.get(field);
    }
    @Test void sameFeeDoesNotDeductOriginalFeeTwiceAndZeroFeeRecoversGrossProfit() {
        assertEquals(0, value(TRADE, 10, 0, "adjustedNetProfit").compareTo(new BigDecimal("9.79")));
        assertEquals(0, value(TRADE, 0, 0, "adjustedNetProfit").compareTo(BigDecimal.TEN));
    }
    @Test void chargesBothSidesWithFeesOnAdverseFillAmounts() {
        assertEquals(0, value(TRADE, 20, 10, "adjustedNetProfit").compareTo(new BigDecimal("9.37002")));
        assertEquals(0, value(TRADE, 20, 10, "estimatedSlippageCost").compareTo(new BigDecimal("0.21")));
        assertEquals(0, value(TRADE, 20, 10, "estimatedFees").compareTo(new BigDecimal("0.41998")));
    }
    @Test void lossCountsDrawdownFromInitialCapitalAndZeroTradesRemainZero() {
        String loss = TRADE.replace("110", "90").replace("9.79", "-10.19");
        assertEquals(0, value(loss, 10, 0, "realizedDrawdown").compareTo(new BigDecimal("0.01019")));
        assertEquals(0, value("{\"totalTrades\":0,\"netProfit\":0,\"trades\":[]}", 20, 10, "adjustedNetProfit").signum());
    }
    @Test void oldIncompleteOrInconsistentEvidenceDoesNotProduceEstimates() {
        for (String json : new String[]{TRADE.replace("\"amount\":1,", ""), TRADE.replace("\"totalTrades\":1", "\"totalTrades\":2"),
                TRADE.replace("\"isShort\":false", "\"isShort\":true"), TRADE.replace("\"netProfit\":9.79,\"trades\"", "\"netProfit\":10,\"trades\""),
                TRADE.replace("\"close_rate\":110", "\"close_rate\":120")})
            assertEquals(false, TradeCostSensitivity.calculate(json, BigDecimal.TEN, 10, 5).get("available"));
        assertThrows(IllegalArgumentException.class, () -> TradeCostSensitivity.calculate(TRADE, BigDecimal.TEN, 101, 0));
        assertThrows(IllegalArgumentException.class, () -> TradeCostSensitivity.calculate(TRADE, BigDecimal.ZERO, 10, 0));
    }
    @Test void realizesInExitOrderAndUsesPeakEquityForLaterLoss() {
        var root = cn.iocoder.yudao.framework.common.util.json.JsonUtils.getObjectMapper().readTree(TRADE);
        var win = root.path("trades").get(0);
        var loss = cn.iocoder.yudao.framework.common.util.json.JsonUtils.getObjectMapper().readTree(
                TRADE.replace("110", "90").replace("9.79", "-10.19").replace("01:00:00", "02:00:00")).path("trades").get(0);
        var json = cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(java.util.Map.of(
                "totalTrades", 2, "netProfit", new BigDecimal("-0.4"), "trades", java.util.List.of(loss, win)));
        var expected = new BigDecimal("10.19").divide(new BigDecimal("1009.79"), 12, java.math.RoundingMode.HALF_UP);
        assertEquals(0, expected.compareTo(value(json, 10, 0, "realizedDrawdown")));
    }
}
