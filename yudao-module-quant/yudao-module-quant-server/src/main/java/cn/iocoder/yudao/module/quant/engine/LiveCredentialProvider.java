package cn.iocoder.yudao.module.quant.engine;

import java.util.Optional;

public interface LiveCredentialProvider {
    Optional<OkxCredential> load();
    boolean configured();
    record OkxCredential(String apiKey,String secretKey,String passphrase) {}
}
