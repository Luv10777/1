package com.wuyao.growth.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Prevents accidentally deploying local development credentials with the prod profile.
 */
@Component
@Profile("prod")
public class ProductionConfigurationValidator {
    private final String storageEndpoint;
    private final String storageAccessKey;
    private final String storageSecretKey;
    private final String dbPassword;
    private final String redisHost;

    public ProductionConfigurationValidator(
            @Value("${growth.storage.endpoint}") String storageEndpoint,
            @Value("${growth.storage.access-key}") String storageAccessKey,
            @Value("${growth.storage.secret-key}") String storageSecretKey,
            @Value("${spring.datasource.password}") String dbPassword,
            @Value("${spring.data.redis.host}") String redisHost) {
        this.storageEndpoint = storageEndpoint;
        this.storageAccessKey = storageAccessKey;
        this.storageSecretKey = storageSecretKey;
        this.dbPassword = dbPassword;
        this.redisHost = redisHost;
    }

    @PostConstruct
    void validate() {
        if (storageEndpoint.contains("localhost") || storageEndpoint.contains("127.0.0.1")) {
            throw new IllegalStateException("生产环境不能使用本地对象存储地址");
        }
        if (isDevCredential(storageAccessKey) || isDevCredential(storageSecretKey)
                || isDevCredential(dbPassword)) {
            throw new IllegalStateException("生产环境检测到开发环境默认凭证");
        }
        if (redisHost.isBlank() || "localhost".equalsIgnoreCase(redisHost)
                || "127.0.0.1".equals(redisHost)) {
            throw new IllegalStateException("生产环境必须配置独立 Redis 地址");
        }
    }

    private boolean isDevCredential(String value) {
        return value == null || value.isBlank()
                || value.equals("minioadmin")
                || value.equals("growth_dev_local");
    }
}
