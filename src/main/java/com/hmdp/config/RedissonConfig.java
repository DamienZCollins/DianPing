package com.hmdp.config;

import org.redisson.config.SingleServerConfig;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 配置类
 * <p>
 * 注意：Redis 连接信息（host/port/password/database）由
 * {@code redisson-spring-boot-starter} 基于 {@code spring.redis.*} 自动装配，
 * 这里只通过 {@link RedissonAutoConfigurationCustomizer} 追加自定义的
 * 连接池、超时与重试参数，避免完全覆盖 starter 的默认行为。
 */
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonAutoConfigurationCustomizer redissonCustomizer() {
        return config -> {
            SingleServerConfig single = config.useSingleServer();
            single.setConnectionPoolSize(64)
                    .setConnectionMinimumIdleSize(10)
                    .setIdleConnectionTimeout(10000)
                    .setConnectTimeout(10000)
                    .setTimeout(3000)
                    .setRetryAttempts(3)
                    .setRetryInterval(1500);
        };
    }
}
