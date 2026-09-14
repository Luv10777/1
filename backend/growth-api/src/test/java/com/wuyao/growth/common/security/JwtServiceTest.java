package com.wuyao.growth.common.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class JwtServiceTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "short-secret", "dev-only-secret-change-me-0123456789abcdef"})
    void missingWeakOrPublicDefaultSecretsPreventStartup(String secret) {
        assertThatThrownBy(() -> new JwtService(secret, Duration.ofHours(1), Duration.ofDays(30)))
                .isInstanceOf(IllegalStateException.class);
    }
}
