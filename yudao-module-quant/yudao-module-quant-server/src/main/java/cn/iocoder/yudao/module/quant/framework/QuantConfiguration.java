package cn.iocoder.yudao.module.quant.framework;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(QuantProperties.class)
public class QuantConfiguration { }
