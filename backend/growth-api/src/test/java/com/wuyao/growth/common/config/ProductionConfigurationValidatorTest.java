package com.wuyao.growth.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProductionConfigurationValidatorTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    String internal = "https://storage.example.com";
    String publicEndpoint = "https://assets.example.com";
    String redisHost = "redis.example.com";
    String redisPassword = "random-redis-secret";
    String sms = "aliyun";
    String app = "growth_app";
    String owner = "growth_owner";
    String storageKey = "random-storage-key";
    String dbPassword = "random-db-secret";

    @Test void acceptsConfiguredProductionServicesAndRestrictedDatabaseRole() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(false);
        assertThatCode(() -> validator().validate()).doesNotThrowAnyException();
    }
    @Test void rejectsHttpStorageAndLoopbackEndpoints() {
        for (String endpoint : java.util.List.of("http://storage.example.com", "https://127.0.0.2", "https://[::1]", "https://localhost")) {
            internal = endpoint;
            assertThatThrownBy(() -> validator().validate()).isInstanceOf(IllegalStateException.class);
        }
        internal = "https://storage.example.com";
        publicEndpoint = "http://assets.example.com";
        assertThatThrownBy(() -> validator().validate()).hasMessageContaining("HTTPS");
    }
    @Test void rejectsConsoleSmsAndUnauthenticatedRedis() {
        sms = "console";
        assertThatThrownBy(() -> validator().validate()).hasMessageContaining("短信");
        sms = "aliyun";
        redisPassword = "";
        assertThatThrownBy(() -> validator().validate()).hasMessageContaining("Redis");
        redisPassword = "random-redis-secret";
        redisHost = "::1";
        assertThatThrownBy(() -> validator().validate()).hasMessageContaining("Redis");
    }
    @Test void rejectsMigrationUserAndPrivilegedRuntimeRole() {
        app = owner;
        assertThatThrownBy(() -> validator().validate()).hasMessageContaining("迁移账号分离");
        app = "growth_app";
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);
        assertThatThrownBy(() -> validator().validate()).hasMessageContaining("BYPASSRLS");
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(null);
        assertThatThrownBy(() -> validator().validate()).hasMessageContaining("BYPASSRLS");
    }
    @Test void rejectsDevelopmentCredentials() {
        dbPassword = "growth_dev_local";
        assertThatThrownBy(() -> validator().validate()).hasMessageContaining("默认凭证");
        dbPassword = "random-db-secret";
        storageKey = "minioadmin";
        assertThatThrownBy(() -> validator().validate()).hasMessageContaining("默认凭证");
    }
    private ProductionConfigurationValidator validator() {
        return new ProductionConfigurationValidator(internal, storageKey, "random-storage-secret", dbPassword,
                redisHost, publicEndpoint, redisPassword, sms, app, owner, jdbc);
    }
}
