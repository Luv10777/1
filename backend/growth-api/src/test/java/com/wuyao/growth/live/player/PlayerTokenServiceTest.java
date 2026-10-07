package com.wuyao.growth.live.player;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class PlayerTokenServiceTest {
    @Test
    void tokensAreUniqueScopedAndRejectTamperingAndWrongSigningKey() {
        var service = new PlayerTokenService("test-only-random-signing-secret-0123456789abcdef", Duration.ofHours(1));
        var first = service.issue(2L, 9L);
        var second = service.issue(2L, 9L);
        assertThat(first.value()).isNotEqualTo(second.value());
        assertThat(first.hash()).hasSize(64).isNotEqualTo(first.value());
        assertThat(service.parse(first.value()).getSubject()).isEqualTo("9");
        assertThat(service.parse(first.value()).get("tid", Number.class).longValue()).isEqualTo(2L);
        assertThat(service.parse(first.value() + "tampered")).isNull();
        var other = new PlayerTokenService("other-test-only-random-signing-secret-0123456789abcdef", Duration.ofHours(1));
        assertThat(other.parse(first.value())).isNull();
    }

    @Test
    void expiredTokenIsRejected() {
        var service = new PlayerTokenService("test-only-random-signing-secret-0123456789abcdef", Duration.ofSeconds(-1));
        assertThat(service.parse(service.issue(1L, 2L).value())).isNull();
    }
}
