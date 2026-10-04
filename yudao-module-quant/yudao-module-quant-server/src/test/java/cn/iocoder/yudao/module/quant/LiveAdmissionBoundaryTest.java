package cn.iocoder.yudao.module.quant;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.dal.LiveAdmissionRepository;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import cn.iocoder.yudao.module.quant.service.LiveAdmissionService;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LiveAdmissionBoundaryTest {
    @Test void explicitIpPolicyChangesNewReportEvidenceWithoutEnablingTrading() {
        var reports=new ArrayList<String>();
        var hashes=new ArrayList<String>();
        var repository=new LiveAdmissionRepository(null) {
            @Override public Map<String,Object> latestSuccessfulBacktest(long tenant,long owner){return null;}
            @Override public Map<String,Object> qualifyingSoak(long tenant,long owner){return null;}
            @Override public Map<String,Object> qualifyingOrderRehearsal(long tenant,long owner){return null;}
            @Override public Map<String,Object> latestRiskStop(long tenant,long owner){return null;}
            @Override public int unresolvedAlertCount(long tenant,long owner){return 0;}
            @Override public String createOrGet(long tenant,long owner,String json,String hash){
                reports.add(json);hashes.add(hash);return "report-"+reports.size();
            }
        };
        var properties=new QuantProperties();
        var service=new LiveAdmissionService(repository,properties);
        service.create(1,10);
        properties.setLiveIpWhitelistRequired(false);
        service.create(1,10);
        var original=JsonUtils.getObjectMapper().readTree(reports.getFirst());
        var exception=JsonUtils.getObjectMapper().readTree(reports.getLast());
        assertTrue(original.path("keyBoundary").path("ipWhitelistRequired").asBoolean());
        assertFalse(exception.path("keyBoundary").path("ipWhitelistRequired").asBoolean());
        assertTrue(exception.path("keyBoundary").path("environmentIsolation").asText().contains("所有者明确接受"));
        assertNotEquals(hashes.getFirst(),hashes.getLast());
        for(var report:List.of(original,exception)){
            assertFalse(report.path("activationAllowed").asBoolean());
            assertFalse(report.path("liveTradingAllowed").asBoolean());
            assertFalse(report.path("keyBoundary").path("withdrawalAllowed").asBoolean());
        }
    }
}
