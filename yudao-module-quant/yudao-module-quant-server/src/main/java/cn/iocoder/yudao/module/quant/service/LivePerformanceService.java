package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.module.quant.dal.LiveAutomationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;

/** Read-only session attribution, exclusively from owned local ledger evidence. */
@Service
public class LivePerformanceService {
    private final JdbcTemplate jdbc;
    private final LiveAutomationRepository sessions;
    public LivePerformanceService(JdbcTemplate jdbc, LiveAutomationRepository sessions) { this.jdbc=jdbc; this.sessions=sessions; }
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    public Map<String,Object> get(long tenant,long owner,String id) {
        var session=sessions.get(tenant,owner,id);
        if(session==null) throw new IllegalArgumentException("Session does not exist");
        // EXISTS avoids duplicate attribution even if more than one signal references an order.
        var rows=jdbc.queryForList("SELECT o.id,o.instrument_id AS instrumentId,o.price,o.amount,o.notional,o.submitted_at AS submittedAt,o.exchange_order_id AS exchangeOrderId,o.client_order_id AS clientOrderId,o.side,o.status,o.filled_amount AS filledAmount,o.average_price AS averagePrice,o.fee_amount AS feeAmount,o.fee_currency AS feeCurrency,o.rebate_amount AS rebateAmount,o.rebate_currency AS rebateCurrency,o.updated_at AS updatedAt FROM quant_live_exchange_order o WHERE o.tenant_id=? AND o.owner_id=? AND o.instrument_id='BTC-USDT' AND o.policy_id=? AND EXISTS (SELECT 1 FROM quant_live_strategy_signal s WHERE s.session_id=? AND s.tenant_id=o.tenant_id AND s.owner_id=o.owner_id AND (s.exchange_order_id=o.id OR s.client_order_id=o.client_order_id)) ORDER BY o.submitted_at,o.id",tenant,owner,session.get("policyId"),id);
        var marks=jdbc.queryForList("SELECT close_price AS price,candle_at AS candleAt FROM quant_live_strategy_signal WHERE tenant_id=? AND owner_id=? AND session_id=? ORDER BY candle_at DESC LIMIT 1",tenant,owner,id);
        BigDecimal mark=marks.isEmpty()?null:(BigDecimal)marks.getFirst().get("price");
        Long at=marks.isEmpty()?null:((Number)marks.getFirst().get("candleAt")).longValue();
        var result=calculate(rows,mark,at);
        result.put("sessionId",id); result.put("status",session.get("status"));
        result.put("accountEquityChange",decimal(session,"lastEquity").subtract(decimal(session,"startEquity")));
        var reports=jdbc.queryForList("SELECT r.id,r.report_hash AS reportHash,r.report_json AS reportJson FROM quant_live_control_policy p JOIN quant_live_admission_report r ON r.id=p.admission_report_id AND r.tenant_id=p.tenant_id AND r.owner_id=p.owner_id WHERE p.id=? AND p.tenant_id=? AND p.owner_id=?",session.get("policyId"),tenant,owner);
        if(!reports.isEmpty()){
            var report=reports.getFirst();var manifest=cn.iocoder.yudao.framework.common.util.json.JsonUtils.getObjectMapper().readTree((String)report.get("reportJson"));
            result.put("admissionReportId",report.get("id"));result.put("admissionReportHash",report.get("reportHash"));
            // Historical views use the confirmed manifest, not a mutable current editor.
            if(manifest.has("strategy"))result.put("strategy",manifest.get("strategy"));
            else result.put("legacyStrategyVersionId",manifest.path("evidence").path("backtest").path("strategyVersionId").asText(manifest.path("evidence").path("backtest").path("strategyversionid").asText()));
        }
        var trace=jdbc.queryForList("SELECT s.id,s.candle_at AS candleAt,s.signal_type AS signalType,s.close_price AS closePrice,s.fast_ema AS fastEma,s.slow_ema AS slowEma,s.status,s.message,s.client_order_id AS clientOrderId,s.exchange_order_id AS ledgerOrderId,s.signal_hash AS signalHash,d.id AS decisionId,d.decision,d.reason_code AS gateReason FROM quant_live_strategy_signal s LEFT JOIN quant_live_order_decision d ON d.id=s.decision_id AND d.tenant_id=s.tenant_id AND d.owner_id=s.owner_id AND d.policy_id=s.policy_id WHERE s.session_id=? AND s.tenant_id=? AND s.owner_id=? ORDER BY s.candle_at DESC,s.id LIMIT 200",id,tenant,owner);
        var byId=new HashMap<String,Map<String,Object>>();var byClient=new HashMap<String,Map<String,Object>>();
        for(var order:rows){byId.put(String.valueOf(order.get("id")),order);byClient.put(String.valueOf(order.get("clientOrderId")),order);}
        for(var signal:trace){
            String message=Objects.toString(signal.get("message"),"");signal.put("signalReason",recordedReason(message));
            var order=signal.get("ledgerOrderId")==null?byClient.get(String.valueOf(signal.get("clientOrderId"))):byId.get(String.valueOf(signal.get("ledgerOrderId")));
            signal.put("order",order);
            signal.put("linkState",order!=null?"LINKED":signal.get("clientOrderId")!=null?"NO_LEDGER_ORDER":"NO_ORDER_EXPECTED");
        }
        result.put("generatedAt",System.currentTimeMillis());result.put("executionTrace",trace);result.put("traceLimit",200);
        result.put("signalCount",jdbc.queryForObject("SELECT COUNT(*) FROM quant_live_strategy_signal WHERE session_id=? AND tenant_id=? AND owner_id=?",Long.class,id,tenant,owner));
        return result;
    }
    public static String recordedReason(String message){
        if(message!=null)for(String reason:List.of("STOP_LOSS","TAKE_PROFIT","EMA_CROSS","NO_CROSS","HOLDING_POSITION","CHANNEL_ENTRY","CHANNEL_EXIT","NO_BREAKOUT"))if(message.startsWith("["+reason+"] "))return reason;
        return "UNRECORDED";
    }
    public static Map<String,Object> calculate(List<Map<String,Object>> rows,BigDecimal mark,Long markAt) {
        BigDecimal buys=BigDecimal.ZERO,sells=BigDecimal.ZERO,position=BigDecimal.ZERO,quoteCosts=BigDecimal.ZERO,baseCosts=BigDecimal.ZERO,convertedCosts=BigDecimal.ZERO;
        int fills=0,missing=0,open=0; boolean complete=true;
        var warnings=new LinkedHashSet<String>(); var details=new ArrayList<Map<String,Object>>();
        for(var row:rows) {
            var detail=new LinkedHashMap<String,Object>();
            for(String key:List.of("id","instrumentId","price","amount","notional","submittedAt","exchangeOrderId","clientOrderId","side","status","filledAmount","averagePrice","feeAmount","feeCurrency","rebateAmount","rebateCurrency","updatedAt")) detail.put(key,row.get(key));
            details.add(detail);
            detail.put("costEvidenceComplete",true);
            if(Set.of("SUBMITTING","SUBMIT_UNKNOWN","LIVE","PARTIALLY_FILLED","CANCEL_REQUESTED").contains(String.valueOf(row.get("status")))) open++;
            BigDecimal amount=decimal(row,"filledAmount"); if(amount.signum()==0) continue;
            fills++; BigDecimal price=(BigDecimal)row.get("averagePrice"); String side=String.valueOf(row.get("side"));
            if(amount.signum()<0||price==null||price.signum()<=0||!Set.of("BUY","SELL").contains(side)){detail.put("costEvidenceComplete",false);complete=false;warnings.add("Invalid fill amount, price or side");continue;}
            BigDecimal cash=amount.multiply(price);
            if("BUY".equals(side)){buys=buys.add(cash);position=position.add(amount);}else{sells=sells.add(cash);position=position.subtract(amount);}
            boolean known=true;
            for(String prefix:List.of("fee","rebate")) {
                BigDecimal cost=(BigDecimal)row.get(prefix+"Amount"); String currency=Objects.toString(row.get(prefix+"Currency"),"");
                if(cost==null){known=false;continue;}
                if(cost.signum()==0)continue;
                if("BTC".equals(currency)){baseCosts=baseCosts.add(cost);convertedCosts=convertedCosts.add(cost.multiply(price));}
                else if("USDT".equals(currency)){quoteCosts=quoteCosts.add(cost);convertedCosts=convertedCosts.add(cost);}
                else {known=false;warnings.add("Unsupported cost currency: "+currency);}
            }
            if(!known){missing++;complete=false;warnings.add("Actual fee/rebate evidence is incomplete");}
            detail.put("costEvidenceComplete",known);
        }
        BigDecimal grossPosition=position; position=position.add(baseCosts);
        if(position.signum()<0){complete=false;warnings.add("Session sold more BTC than its attributed inventory");}
        boolean priceAvailable=position.signum()==0||(mark!=null&&mark.signum()>0);
        if(!priceAvailable) warnings.add("No valid closed-candle valuation price");
        BigDecimal netCash=sells.subtract(buys).add(quoteCosts);
        var result=new LinkedHashMap<String,Object>();
        result.put("basis","SESSION_ORDER_CASH_FLOW_PLUS_CLOSED_CANDLE_MARK");
        result.put("orderCount",rows.size());result.put("filledOrderCount",fills);result.put("activeOrderCount",open);result.put("missingCostOrderCount",missing);
        result.put("buyTurnover",buys);result.put("sellTurnover",sells);result.put("grossCashFlow",sells.subtract(buys));result.put("grossPositionBtc",grossPosition);
        result.put("knownSignedCostsUsdt",convertedCosts);result.put("costsComplete",complete);
        result.put("netPositionBtc",complete?position:null);result.put("netCashFlow",complete?netCash:null);
        result.put("markPrice",mark);result.put("markCandleAt",markAt);
        result.put("markedPositionValue",complete&&priceAvailable?position.multiply(position.signum()==0?BigDecimal.ZERO:mark):null);
        result.put("netContribution",complete&&priceAvailable?netCash.add(position.multiply(position.signum()==0?BigDecimal.ZERO:mark)):null);
        String settlementState=!complete?"INCOMPLETE_EVIDENCE":open>0?"ACTIVE_ORDERS":fills==0?"NO_FILLS":position.signum()!=0?"OPEN_POSITION":"CLOSED_POSITION";
        result.put("settlementState",settlementState);
        result.put("closedPositionNetPnl","CLOSED_POSITION".equals(settlementState)?netCash:null);
        result.put("valuationComplete",complete&&priceAvailable);result.put("warnings",new ArrayList<>(warnings));result.put("orders",details);
        return result;
    }
    private static BigDecimal decimal(Map<String,Object> row,String key){Object value=row.get(key);return value==null?BigDecimal.ZERO:new BigDecimal(value.toString());}
}
