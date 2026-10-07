package com.wuyao.growth.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import java.net.URI;
import java.util.Set;

/**
 * Prevents accidentally deploying local development credentials with the prod profile.
 */
@Component
@Profile("prod")
@DependsOnDatabaseInitialization
public class ProductionConfigurationValidator {
    private final String storageEndpoint;
    private final String publicStorageEndpoint;
    private final String storageAccessKey;
    private final String storageSecretKey;
    private final String dbPassword;
    private final String redisHost;
    private final String redisPassword;
    private final String smsProvider;
    private final String dbUsername;
    private final String flywayUsername;
    private final JdbcTemplate jdbc;

    public ProductionConfigurationValidator(
            @Value("${growth.storage.endpoint}") String storageEndpoint,
            @Value("${growth.storage.access-key}") String storageAccessKey,
            @Value("${growth.storage.secret-key}") String storageSecretKey,
            @Value("${spring.datasource.password}") String dbPassword,
            @Value("${spring.data.redis.host}") String redisHost,
            @Value("${growth.storage.public-endpoint:${growth.storage.endpoint}}") String publicStorageEndpoint,
            @Value("${spring.data.redis.password:}") String redisPassword,
            @Value("${growth.sms.provider:}") String smsProvider,
            @Value("${spring.datasource.username}") String dbUsername,
            @Value("${spring.flyway.user}") String flywayUsername,
            JdbcTemplate jdbc) {
        this.storageEndpoint = storageEndpoint;
        this.publicStorageEndpoint = publicStorageEndpoint;
        this.storageAccessKey = storageAccessKey;
        this.storageSecretKey = storageSecretKey;
        this.dbPassword = dbPassword;
        this.redisHost = redisHost;
        this.redisPassword = redisPassword;
        this.smsProvider = smsProvider;
        this.dbUsername = dbUsername;
        this.flywayUsername = flywayUsername;
        this.jdbc = jdbc;
    }

    @PostConstruct
    void validate() {
        requireHttps(storageEndpoint, "对象存储内部地址");
        requireHttps(publicStorageEndpoint, "对象存储公开地址");
        if (isDevCredential(storageAccessKey) || isDevCredential(storageSecretKey)
                || isDevCredential(dbPassword)) {
            throw new IllegalStateException("生产环境检测到开发环境默认凭证");
        }
        if (isLoopbackOrBlank(redisHost)) {
            throw new IllegalStateException("生产环境必须配置独立 Redis 地址");
        }
        if (redisPassword == null || redisPassword.isBlank()) {
            throw new IllegalStateException("生产环境 Redis 必须配置认证密码");
        }
        if (!"aliyun".equalsIgnoreCase(smsProvider)) {
            throw new IllegalStateException("生产环境必须使用已支持的真实短信服务商 aliyun");
        }
        if (dbUsername == null || dbUsername.isBlank() || flywayUsername == null
                || flywayUsername.isBlank() || dbUsername.equals(flywayUsername)) {
            throw new IllegalStateException("生产环境应用数据库账号必须与迁移账号分离");
        }
        Boolean unsafeRole = jdbc.queryForObject("""
                SELECT r.rolsuper OR r.rolbypassrls OR EXISTS (
                    SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') AND c.relowner = r.oid
                ) FROM pg_roles r WHERE r.rolname = current_user
                """, Boolean.class);
        if (!Boolean.FALSE.equals(unsafeRole)) {
            throw new IllegalStateException("生产环境应用数据库账号不能拥有超级用户、BYPASSRLS 或业务表所有权");
        }
    }

    private void requireHttps(String endpoint, String label) {
        try {
            URI uri = endpoint == null ? null : URI.create(endpoint);
            if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null) {
                throw new IllegalStateException("生产环境" + label + "必须使用 HTTPS");
            }
            if (isLoopbackOrBlank(uri.getHost())) throw new IllegalStateException("生产环境不能使用本地对象存储地址");
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("生产环境" + label + "地址无效", e);
        }
    }

    private boolean isLoopbackOrBlank(String host) {
        if (host == null || host.isBlank()) return true;
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        return Set.of("localhost", "::1", "[::1]", "0.0.0.0", "::", "[::]").contains(normalized)
                || normalized.startsWith("127.") || normalized.endsWith(".localhost");
    }

    private boolean isDevCredential(String value) {
        return value == null || value.isBlank()
                || value.equals("minioadmin")
                || value.equals("growth_dev_local");
    }
}
