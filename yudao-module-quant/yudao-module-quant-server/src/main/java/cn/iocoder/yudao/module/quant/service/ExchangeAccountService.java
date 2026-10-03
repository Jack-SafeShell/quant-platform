package cn.iocoder.yudao.module.quant.service;
import cn.iocoder.yudao.module.quant.api.backtest.ExchangeAccountRequest;
import cn.iocoder.yudao.module.quant.dal.ExchangeAccountRepository;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.Map;
@Service
public class ExchangeAccountService {
    private final JdbcTemplate jdbc;private final ExchangeAccountRepository accounts;private final QuantProperties properties;
    public ExchangeAccountService(JdbcTemplate jdbc,ExchangeAccountRepository accounts,QuantProperties properties){this.jdbc=jdbc;this.accounts=accounts;this.properties=properties;}
    public Map<String,Object> current(){var row=accounts.current();return Map.of("id",row.get("id"),"exchange",row.get("exchangeName"),"identityVerified",row.get("identityHash")!=null,"privateExecutionSupported","okx".equals(row.get("exchangeName")),"selectionMode","MONOLITH_CONFIGURED_ACCOUNT");}
    public String register(ExchangeAccountRequest request){
        if(properties.isLiveExecutionEnabled()||properties.isLiveAutomationEnabled())throw new IllegalArgumentException("Disable live execution before registering an account");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM quant_exchange_account WHERE id=?",Integer.class,request.id())>0)throw new IllegalArgumentException("Account identity and credential reference are immutable");
        jdbc.update("INSERT INTO quant_exchange_account(id,exchange_name,credential_file_name,created_at) VALUES(?,?,?,?)",request.id(),request.exchange(),request.credentialFileName(),System.currentTimeMillis());return request.id();
    }
}
