package cn.iocoder.yudao.module.quant;

import cn.iocoder.yudao.module.quant.dal.*;
import cn.iocoder.yudao.module.quant.engine.DatasetRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PaperExchangeBindingTest {
    JdbcTemplate jdbc(){return new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));}
    @Test void paperExchangeComesFromOwnedSuccessfulResearchTasks(){
        var jdbc=jdbc();jdbc.execute("CREATE TABLE quant_paper_session(id VARCHAR,batch_id VARCHAR,parameter_set_id VARCHAR,tenant_id BIGINT,owner_id BIGINT)");jdbc.execute("CREATE TABLE quant_optimization_member(batch_id VARCHAR,parameter_set_id VARCHAR,task_id VARCHAR)");jdbc.execute("CREATE TABLE quant_backtest_task(id VARCHAR,exchange_name VARCHAR,tenant_id BIGINT,owner_id BIGINT,status VARCHAR)");
        jdbc.update("INSERT INTO quant_paper_session VALUES('s','b','p',1,10)");jdbc.update("INSERT INTO quant_optimization_member VALUES('b','p','train'),('b','p','validation')");jdbc.update("INSERT INTO quant_backtest_task VALUES('train','binance',1,10,'SUCCEEDED'),('validation','binance',1,10,'SUCCEEDED')");
        var repo=new PaperSessionRepository(jdbc);assertEquals("binance",repo.exchange(1,10,"s"));assertThrows(IllegalArgumentException.class,()->repo.exchange(1,11,"s"));jdbc.update("UPDATE quant_backtest_task SET exchange_name='okx' WHERE id='train'");assertThrows(IllegalArgumentException.class,()->repo.exchange(1,10,"s"));
    }
    @Test void candidatePaperExchangeRequiresOwnedBoundAndIntactReadiness(){
        var jdbc=jdbc();jdbc.execute("CREATE TABLE quant_paper_execution(id VARCHAR,readiness_snapshot_id VARCHAR,readiness_hash VARCHAR,tenant_id BIGINT,owner_id BIGINT)");jdbc.execute("CREATE TABLE quant_paper_readiness_snapshot(id VARCHAR,manifest_json VARCHAR,manifest_hash VARCHAR)");String json="{\"config\":{\"exchange\":\"binance\"}}",hash=DatasetRegistry.hash(json.getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO quant_paper_readiness_snapshot VALUES('r',?,?)",json,hash);jdbc.update("INSERT INTO quant_paper_execution VALUES('e','r',?,1,10)",hash);var repo=new LiveAdmissionRepository(jdbc);assertEquals("binance",repo.paperExchange(1,10,"e"));assertEquals("",repo.paperExchange(1,11,"e"));jdbc.update("UPDATE quant_paper_readiness_snapshot SET manifest_json=?",json.replace("binance","okx"));assertEquals("",repo.paperExchange(1,10,"e"));jdbc.update("UPDATE quant_paper_readiness_snapshot SET manifest_json=?",json);jdbc.update("UPDATE quant_paper_execution SET readiness_hash='wrong'");assertEquals("",repo.paperExchange(1,10,"e"));
    }
}
