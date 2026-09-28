package com.wuyao.growth.common.gateway;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImageDownloadOriginsTest {
    @Test
    void aliyunWildcardMatchesHttpsSubdomainsOnly() {
        var rules = List.of("*.aliyuncs.com");
        for (String url : List.of(
            "https://bucket-name.oss-cn-hangzhou.aliyuncs.com/image.png?signature=secret",
            "https://bucket-name.oss-cn-shanghai-internal.aliyuncs.com/image.png",
            "https://bucket-name.oss-us-west-1.aliyuncs.com/image.png")) {
            assertThat(ImageDownloadOrigins.allows(ImageDownloadOrigins.checkedUri(url), rules))
                .as(url).isTrue();
        }
        for (String url : List.of(
            "https://aliyuncs.com/image.png",
            "https://evilaliyuncs.com/image.png",
            "https://bucket.oss-cn-hangzhou.aliyuncs.com.evil.example/image.png",
            "http://bucket.oss-cn-hangzhou.aliyuncs.com/image.png",
            "https://bucket.oss-cn-hangzhou.aliyuncs.com:8443/image.png",
            "https://custom-cdn.example.com/image.png")) {
            assertThat(ImageDownloadOrigins.allows(ImageDownloadOrigins.checkedUri(url), rules))
                .as(url).isFalse();
        }
    }

    @Test
    void exactOriginRulesStillWork() {
        var rules = List.of("https://api.onlyrouter.ai", "http://127.0.0.1:8080");
        assertThat(ImageDownloadOrigins.allows(ImageDownloadOrigins.checkedUri(
            "https://api.onlyrouter.ai/v1/files/file-123/content"), rules)).isTrue();
        assertThat(ImageDownloadOrigins.allows(ImageDownloadOrigins.checkedUri(
            "http://127.0.0.1:8080/image.png"), rules)).isTrue();
        assertThat(ImageDownloadOrigins.allows(ImageDownloadOrigins.checkedUri(
            "https://api.onlyrouter.ai.evil.example/image.png"), rules)).isFalse();
    }
}
