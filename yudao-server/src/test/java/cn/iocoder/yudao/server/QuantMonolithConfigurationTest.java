package cn.iocoder.yudao.server;

import cn.iocoder.yudao.module.quant.framework.QuantConfiguration;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class QuantMonolithConfigurationTest {
    @Test void monolithImportsQuantConfigurationWithoutStartingInfrastructure() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(QuantConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(QuantProperties.class);
                    QuantProperties properties = context.getBean(QuantProperties.class);
                    assertThat(properties.isEnabled()).isFalse();
                    assertThat(properties.isPaperExecutionEnabled()).isFalse();
                    assertThat(context.getEnvironment().getProperty("yudao.quant.paper-execution-enabled")).isEqualTo("false");
                    assertThat(properties.getPaperStartTokenTtlSeconds()).isEqualTo(300);
                    assertThat(properties.getPaperSnapshotRetentionDays()).isEqualTo(30);
                    assertThat(properties.getPaperRiskPolicyVersion()).isEqualTo("paper-risk-v1");
                    assertThat(properties.getPaperMaxOrderNotional()).isEqualByComparingTo("100");
                    assertThat(properties.getPaperMaxTotalExposure()).isEqualByComparingTo("100");
                    assertThat(properties.getPaperMaxOpenPositions()).isEqualTo(1);
                    assertThat(properties.getPaperMaxDailyLoss()).isEqualByComparingTo("20");
                    assertThat(properties.getWorkspace().replace('\\', '/')).contains("quant-platform").endsWith(".runtime/quant");
                    assertThat(properties.getImage()).startsWith("freqtradeorg/freqtrade@sha256:");
                    assertThat(context.getEnvironment().getProperty("yudao.quant.timeout-seconds")).isEqualTo("600");
                });
    }
}
