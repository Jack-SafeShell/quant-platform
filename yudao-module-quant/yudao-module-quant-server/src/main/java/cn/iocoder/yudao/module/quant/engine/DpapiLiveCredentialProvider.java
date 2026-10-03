package cn.iocoder.yudao.module.quant.engine;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
public class DpapiLiveCredentialProvider implements LiveCredentialProvider {
    private final QuantProperties properties;
    public DpapiLiveCredentialProvider(QuantProperties properties){this.properties=properties;}
    public boolean configured(){return !properties.getLiveCredentialFile().isBlank()&&Files.isRegularFile(Path.of(properties.getLiveCredentialFile()));}
    public Optional<OkxCredential> load(){
        if(!configured())return Optional.empty();
        try{
            Process process=new ProcessBuilder("pwsh","-NoProfile","-NonInteractive","-File",properties.getLiveCredentialReaderScript(),"-CredentialPath",properties.getLiveCredentialFile()).redirectErrorStream(true).start();
            if(!process.waitFor(15,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("读取加密凭据超时");}
            byte[] output=process.getInputStream().readNBytes(8192);
            if(process.exitValue()!=0)throw new IllegalStateException("无法读取加密凭据");
            return Optional.of(decode(new String(output,StandardCharsets.UTF_8),properties.getLiveExchange()));
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("读取加密凭据被中断");}catch(Exception e){if(e instanceof IllegalStateException state)throw state;throw new IllegalStateException("无法读取加密凭据");}
    }
    static OkxCredential decode(String json,String selectedExchange){
            var node=JsonUtils.getObjectMapper().readTree(json);
            String apiKey=node.path("apiKey").asText(),secretKey=node.path("secretKey").asText(),passphrase=node.path("passphrase").asText();
            String exchange=node.path("exchange").asText("okx"); // Existing untagged files belong to OKX only.
            if(!exchange.equals(selectedExchange)||!java.util.Set.of("okx","binance").contains(exchange))throw new IllegalStateException("加密凭据交易所不匹配");
            if(apiKey.isBlank()||secretKey.isBlank()||("okx".equals(exchange)&&passphrase.isBlank()))throw new IllegalStateException("加密凭据字段不完整");
            return new OkxCredential(apiKey,secretKey,passphrase);
    }
}
