package cn.iocoder.yudao.module.quant;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.BacktestRequest;
import cn.iocoder.yudao.module.quant.dal.BacktestRepository;
import cn.iocoder.yudao.module.quant.engine.*;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import cn.iocoder.yudao.module.quant.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: real project MySQL + real Freqtrade, public historical data only. */
@EnabledIfEnvironmentVariable(named = "QUANT_SMOKE", matches = "true")
class QuantSmokeTest {
    @Test void actualEngineToMysql() throws Exception {
        String url=System.getenv("QUANT_TEST_JDBC_URL");
        assertTrue(url.startsWith("jdbc:mysql://rm-wz92g1um13bcrw3muro.mysql.rds.aliyuncs.com:3306/quant-platform?"));
        var ds=new DriverManagerDataSource(url,System.getenv("QUANT_TEST_DB_USER"),System.getenv("QUANT_TEST_DB_PASSWORD"));
        var props=new QuantProperties(); props.setEnabled(true); props.setWorkspace(System.getenv("QUANT_WORKSPACE"));
        props.setExchangeProxy(System.getenv().getOrDefault("QUANT_EXCHANGE_PROXY", ""));
        props.setTimeoutSeconds(180);
        var repository=new BacktestRepository(new JdbcTemplate(ds)); var datasets=new DatasetRegistry(props);
        var manager=new DataSourceTransactionManager(ds);
        var service=new BacktestService(repository,datasets,props,manager);
        String key="smoke-"+UUID.randomUUID();
        String version=(String)service.listStrategyVersions(1,1).getFirst().get("id");
        var params=new BacktestRequest(key,version,"okx-btc-202608","2026-08-01","2026-09-01",new BigDecimal("1000"),new BigDecimal("100"),new BigDecimal("0.001"));
        String id=service.create(1,1,params);
        assertEquals(id,service.create(1,1,params));
        var worker=new BacktestWorker(repository,new FreqtradeBacktestEngine(props),datasets,props,manager);
        worker.tick();
        var result=service.get(1,1,id);
        Files.writeString(Path.of(props.getWorkspace()).resolve("smoke-result.json"),JsonUtils.toJsonPrettyString(result));
        System.out.println("QUANT_SMOKE task="+id+" status="+result.get("status"));
        assertEquals("SUCCEEDED",result.get("status"),"Inspect workspace/jobs/"+id+"/engine.log");
        assertNotNull(result.get("engineVersion")); assertNotNull(result.get("resultJson"));
    }
}
