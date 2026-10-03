package cn.iocoder.yudao.module.quant.dal;

import cn.iocoder.yudao.module.quant.engine.DatasetRegistry;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Identity is established by the exchange, never by a user supplied balance or account label. */
@Repository
public class ExchangeAccountRepository {
    private final JdbcTemplate jdbc;
    private final QuantProperties properties;
    public ExchangeAccountRepository(JdbcTemplate jdbc,QuantProperties properties){this.jdbc=jdbc;this.properties=properties;}
    public Map<String,Object> current(){
        var rows=jdbc.queryForList("SELECT id,exchange_name AS exchangeName,credential_file_name AS credentialFileName,identity_hash AS identityHash FROM quant_exchange_account WHERE id=?",properties.getLiveAccountId());
        if(rows.isEmpty())throw new IllegalArgumentException("Configured exchange account is not registered");
        var row=rows.getFirst();if(!properties.getLiveExchange().equals(row.get("exchangeName")))throw new IllegalArgumentException("Configured exchange does not match account");return row;
    }
    public void requireCredentialReference(){
        var row=current();Path expected=Path.of(properties.getWorkspace()).toAbsolutePath().normalize().resolve("credentials").resolve(String.valueOf(row.get("credentialFileName")));
        if(properties.getLiveCredentialFile().isBlank()||!expected.equals(Path.of(properties.getLiveCredentialFile()).toAbsolutePath().normalize()))throw new IllegalArgumentException("Encrypted credential file does not belong to configured account");
    }
    public void bindIdentity(String exchangeUid){
        if(exchangeUid==null||!exchangeUid.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("Exchange account identity missing");
        String hash=DatasetRegistry.hash((properties.getLiveExchange()+"/"+exchangeUid).getBytes(StandardCharsets.UTF_8));
        new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()))).executeWithoutResult(s->{
            var row=jdbc.queryForMap("SELECT identity_hash FROM quant_exchange_account WHERE id=? FOR UPDATE",properties.getLiveAccountId());
            if(row.get("identity_hash")!=null&&!hash.equals(row.get("identity_hash")))throw new IllegalArgumentException("Credentials refer to a different exchange account; original ledger cannot be reused");
            if(row.get("identity_hash")==null)jdbc.update("UPDATE quant_exchange_account SET identity_hash=? WHERE id=? AND identity_hash IS NULL",hash,properties.getLiveAccountId());
        });
    }
}
