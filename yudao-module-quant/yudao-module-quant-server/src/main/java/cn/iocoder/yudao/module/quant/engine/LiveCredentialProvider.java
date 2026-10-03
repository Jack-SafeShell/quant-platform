package cn.iocoder.yudao.module.quant.engine;

import java.util.Optional;

public interface LiveCredentialProvider {
    Optional<OkxCredential> load();
    boolean configured();
    // Historical type name retained for compatibility; Binance leaves passphrase empty.
    record OkxCredential(String apiKey,String secretKey,String passphrase) {
        @Override public String toString(){return "LiveCredential[REDACTED]";}
    }
}
