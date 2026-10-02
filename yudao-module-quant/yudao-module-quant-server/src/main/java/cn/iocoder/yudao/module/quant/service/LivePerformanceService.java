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
    public Map<String,Object> get(long tenant,long owner,String id) {
        var session=sessions.get(tenant,owner,id);
        if(session==null) throw new IllegalArgumentException("Session does not exist");
        // EXISTS avoids duplicate attribution even if more than one signal references an order.
        var rows=jdbc.queryForList("SELECT o.id,o.client_order_id AS clientOrderId,o.side,o.status,o.filled_amount AS filledAmount,o.average_price AS averagePrice,o.fee_amount AS feeAmount,o.fee_currency AS feeCurrency,o.rebate_amount AS rebateAmount,o.rebate_currency AS rebateCurrency,o.updated_at AS updatedAt FROM quant_live_exchange_order o WHERE o.tenant_id=? AND o.owner_id=? AND o.instrument_id='BTC-USDT' AND o.policy_id=? AND EXISTS (SELECT 1 FROM quant_live_strategy_signal s WHERE s.session_id=? AND s.tenant_id=o.tenant_id AND s.owner_id=o.owner_id AND (s.exchange_order_id=o.id OR s.client_order_id=o.client_order_id)) ORDER BY o.submitted_at,o.id",tenant,owner,session.get("policyId"),id);
        var marks=jdbc.queryForList("SELECT close_price AS price,candle_at AS candleAt FROM quant_live_strategy_signal WHERE tenant_id=? AND owner_id=? AND session_id=? ORDER BY candle_at DESC LIMIT 1",tenant,owner,id);
        BigDecimal mark=marks.isEmpty()?null:(BigDecimal)marks.getFirst().get("price");
        Long at=marks.isEmpty()?null:((Number)marks.getFirst().get("candleAt")).longValue();
        var result=calculate(rows,mark,at);
        result.put("sessionId",id); result.put("status",session.get("status"));
        result.put("accountEquityChange",decimal(session,"lastEquity").subtract(decimal(session,"startEquity")));
        return result;
    }
    public static Map<String,Object> calculate(List<Map<String,Object>> rows,BigDecimal mark,Long markAt) {
        BigDecimal buys=BigDecimal.ZERO,sells=BigDecimal.ZERO,position=BigDecimal.ZERO,quoteCosts=BigDecimal.ZERO,baseCosts=BigDecimal.ZERO,convertedCosts=BigDecimal.ZERO;
        int fills=0,missing=0,open=0; boolean complete=true;
        var warnings=new LinkedHashSet<String>(); var details=new ArrayList<Map<String,Object>>();
        for(var row:rows) {
            var detail=new LinkedHashMap<String,Object>();
            for(String key:List.of("id","clientOrderId","side","status","filledAmount","averagePrice","feeAmount","feeCurrency","rebateAmount","rebateCurrency","updatedAt")) detail.put(key,row.get(key));
            details.add(detail);
            if(Set.of("SUBMITTING","SUBMIT_UNKNOWN","LIVE","PARTIALLY_FILLED","CANCEL_REQUESTED").contains(String.valueOf(row.get("status")))) open++;
            BigDecimal amount=decimal(row,"filledAmount"); if(amount.signum()==0) continue;
            fills++; BigDecimal price=(BigDecimal)row.get("averagePrice"); String side=String.valueOf(row.get("side"));
            if(amount.signum()<0||price==null||price.signum()<=0||!Set.of("BUY","SELL").contains(side)){complete=false;warnings.add("Invalid fill amount, price or side");continue;}
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
        result.put("valuationComplete",complete&&priceAvailable);result.put("warnings",new ArrayList<>(warnings));result.put("orders",details);
        return result;
    }
    private static BigDecimal decimal(Map<String,Object> row,String key){Object value=row.get(key);return value==null?BigDecimal.ZERO:new BigDecimal(value.toString());}
}
