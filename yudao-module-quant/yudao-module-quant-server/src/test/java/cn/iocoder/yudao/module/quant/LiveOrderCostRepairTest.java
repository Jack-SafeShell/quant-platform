package cn.iocoder.yudao.module.quant;

import cn.iocoder.yudao.module.quant.dal.LiveOrderRepository;
import cn.iocoder.yudao.module.quant.engine.LiveTradingClient;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import cn.iocoder.yudao.module.quant.service.LiveOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LiveOrderCostRepairTest {
    static class Ledger extends LiveOrderRepository {
        Map<String,Object> row=new HashMap<>(Map.of("id","order","clientOrderId","client","status","FILLED","filledAmount",new BigDecimal("0.0001"),"averagePrice",new BigDecimal("85000")));
        int writes;
        Ledger(){super(null);}
        public Map<String,Object> get(long tenant,long owner,String id){return tenant==1&&owner==10?row:null;}
        public void updateCosts(String id,String status,BigDecimal filled,BigDecimal average,String code,String message,BigDecimal fee,String currency,BigDecimal rebate,String rebateCurrency){writes++;row.put("fee",fee);row.put("rebate",rebate);}
    }
    static class Client implements LiveTradingClient {
        int reads;
        String response="{\"code\":\"0\",\"data\":[{\"clOrdId\":\"client\",\"state\":\"filled\",\"accFillSz\":\"0.0001\",\"avgPx\":\"85000\",\"fee\":\"-0.0000001\",\"feeCcy\":\"BTC\",\"rebate\":\"\",\"rebateCcy\":\"\"}]}";
        public boolean configured(){return true;}
        public String getOrder(String id){assertEquals("client",id);reads++;return response;}
        public String accountBalance(){throw new AssertionError("Unexpected balance read");}
        public String pendingOrders(){throw new AssertionError("Unexpected pending read");}
        public String placeSpotLimitOrder(String c,String s,String p,String a){throw new AssertionError("Must never place an order");}
        public String cancelOrder(String id){throw new AssertionError("Must never cancel an order");}
    }
    static LiveOrderService service(Ledger ledger,Client client){return new LiveOrderService(null,ledger,client,new QuantProperties(),null,new DataSourceTransactionManager());}
    @Test void filledAndPartiallyFilledCanceledOrdersCanRepairActualCosts(){
        for(String state:List.of("FILLED","CANCELED")){
            var ledger=new Ledger();ledger.row.put("status",state);var client=new Client();
            if(state.equals("CANCELED"))client.response=client.response.replace("\"filled\"","\"canceled\"");
            service(ledger,client).refresh(1,10,"order");
            assertEquals(1,client.reads);assertEquals(1,ledger.writes);assertEquals(state,ledger.row.get("status"));
            assertEquals(new BigDecimal("-0.0000001"),ledger.row.get("fee"));assertEquals(BigDecimal.ZERO,ledger.row.get("rebate"));
        }
    }
    @Test void conflictingExecutionOrMissingCostsCannotRewriteFinalLedger(){
        for(String response:List.of("state","amount","price","identity","fee")){
            var ledger=new Ledger();var client=new Client();
            client.response=switch(response){
                case "state"->client.response.replace("\"filled\"","\"live\"");
                case "amount"->client.response.replace("0.0001","0.0002");
                case "price"->client.response.replace("85000","86000");
                case "identity"->client.response.replace("\"client\"","\"other\"");
                default->client.response.replace("-0.0000001","");
            };
            assertThrows(IllegalStateException.class,()->service(ledger,client).refresh(1,10,"order"));assertEquals(0,ledger.writes);
        }
    }
    @Test void ownershipAndFailedUnsubmittedOrdersPreventExchangeCalls(){
        var ledger=new Ledger();var client=new Client();var service=service(ledger,client);
        assertThrows(IllegalArgumentException.class,()->service.refresh(1,20,"order"));
        ledger.row.put("status","FAILED");service.refresh(1,10,"order");
        assertEquals(0,client.reads);assertEquals(0,ledger.writes);
    }
}
