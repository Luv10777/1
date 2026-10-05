package com.wuyao.growth.video;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VideoUrlSecurityTest {
    @Test
    void acceptsHttpsPublicHost() {
        assertThat(VideoUrlSecurity.checkedHttps("https://example.com/result.mp4").getHost())
                .isEqualTo("example.com");
    }

    @Test
    void rejectsNonHttpsAndPrivateAddresses() {
        for (String url : new String[]{
                "http://example.com/result.mp4",
                "https://127.0.0.1/result.mp4",
                "https://localhost/result.mp4",
                "https://10.0.0.8/result.mp4",
                "https://example.com/result.mp4#fragment",
                "https://user:pass@example.com/result.mp4"}) {
            assertThatThrownBy(() -> VideoUrlSecurity.checkedHttps(url))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
