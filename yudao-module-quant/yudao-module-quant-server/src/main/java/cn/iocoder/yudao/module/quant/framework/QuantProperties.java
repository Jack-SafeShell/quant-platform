package cn.iocoder.yudao.module.quant.framework;

import lombok.Data;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.math.BigDecimal;

@Data
@Validated
@ConfigurationProperties(prefix = "yudao.quant")
public class QuantProperties {
    private boolean enabled = false;
    private boolean paperExecutionEnabled = false;
    @Min(60) @Max(900) private int paperStartTokenTtlSeconds = 300;
    @Min(10) @Max(3600) private int paperObservationIntervalSeconds = 60;
    @Min(1) @Max(3650) private int paperSnapshotRetentionDays = 30;
    @NotBlank private String paperRiskPolicyVersion = "paper-risk-v1";
    @DecimalMin("0.01") private BigDecimal paperMaxOrderNotional = new BigDecimal("100");
    @DecimalMin("0.01") private BigDecimal paperMaxTotalExposure = new BigDecimal("100");
    @Min(1) @Max(100) private int paperMaxOpenPositions = 1;
    @DecimalMin("0.01") private BigDecimal paperMaxDailyLoss = new BigDecimal("20");
    @DecimalMin("0.0001") @DecimalMax("1.0") private BigDecimal paperMaxDrawdownRatio = new BigDecimal("0.05");
    private boolean liveExecutionEnabled = false;
    @NotBlank private String liveRiskPolicyVersion = "live-risk-v1";
    @NotBlank private String liveExchange = "okx";
    @NotBlank private String livePair = "BTC/USDT";
    @DecimalMin("0.01") private BigDecimal liveMaxOrderNotional = new BigDecimal("10");
    @DecimalMin("0.01") private BigDecimal liveMaxDailyNotional = new BigDecimal("20");
    @DecimalMin("0.01") private BigDecimal liveMaxTotalExposure = new BigDecimal("20");
    @Min(1) @Max(100) private int liveMaxOpenOrders = 1;
    @Min(30) @Max(900) private int liveOrderTokenTtlSeconds = 180;
    @Min(10) @Max(3600) private int liveOrderCancelTimeoutSeconds = 60;
    private boolean liveAutomationEnabled = false;
    @Min(5) @Max(300) private int liveAutomationIntervalSeconds = 15;
    @DecimalMin("0.01") private BigDecimal liveAutomationOrderNotional = new BigDecimal("5");
    @DecimalMin("0.01") private BigDecimal liveMaxSessionLoss = new BigDecimal("5");
    @Min(1) @Max(5) private int livePrivateReadMaxAttempts = 3;
    @Min(50) @Max(5000) private int livePrivateReadRetryDelayMillis = 500;
    private String liveCredentialFile = "";
    @NotBlank private String liveCredentialReaderScript = "D:/0000/quant-platform/script/quant/read_live_credential.ps1";
    @Pattern(regexp = "https://(openapi|www|us|eea)\\.okx\\.com") private String liveOkxBaseUrl = "https://openapi.okx.com";
    @NotBlank private String workspace = ".runtime/quant";
    @NotBlank private String dockerExecutable = "docker";
    @Pattern(regexp = "freqtradeorg/freqtrade@sha256:[0-9a-f]{64}")
    private String image = "freqtradeorg/freqtrade@sha256:7031bca43ed7668ebf421725dd5016acade6ef88b0771db3e08c96e6d19a42db";
    private String exchangeProxy = "";
    private String datasetHttpProxy = "";
    @NotBlank private String pythonExecutable = "python";
    @NotBlank private String datasetScript = "script/quant/prepare_dataset.py";
    @Min(30) @Max(3600) private int timeoutSeconds = 600;
}
