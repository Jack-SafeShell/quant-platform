package cn.iocoder.yudao.module.quant.framework;

import lombok.Data;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "yudao.quant")
public class QuantProperties {
    private boolean enabled = false;
    @NotBlank private String workspace = ".runtime/quant";
    @NotBlank private String dockerExecutable = "docker";
    @Pattern(regexp = "freqtradeorg/freqtrade@sha256:[0-9a-f]{64}")
    private String image = "freqtradeorg/freqtrade@sha256:7031bca43ed7668ebf421725dd5016acade6ef88b0771db3e08c96e6d19a42db";
    private String exchangeProxy = "";
    @Min(30) @Max(3600) private int timeoutSeconds = 600;
}
