package com.wuyao.growth.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CustomerMessagesTest {

    private static final String FALLBACK = "视频没有生成成功，请稍后重试。";

    @Test
    void relayRatioErrorBecomesPlainChinese() {
        String raw = "供应商响应 HTTP 400: {\"code\":\"fail_to_fetch_task\",\"message\":\"{\\\"code\\\":\\\"fail_to_fetch_task\\\","
                + "\\\"message\\\":\\\"{\\\\\\\"error\\\\\\\":{\\\\\\\"code\\\\\\\":\\\\\\\"InvalidParam\\\\\\\",\\\\\\\"message\\\\\\\":"
                + "\\\\\\\"字段 ratio 取值 不在允许范围 [16:9 4:3 1:1 3:4 9:16 21:9 adaptive]\\\\\\\"}}\\\",\\\"data\\\":null}\",\"data\":null}";
        assertThat(CustomerMessages.plain(raw, FALLBACK)).isEqualTo("这个模型不支持当前的画面比例，换一个比例再试试。");
    }

    @Test
    void realFacesAreExplainedBeforeGenericModeration() {
        assertThat(CustomerMessages.plain("InputImageSensitiveContentDetected.PrivacyInformation: The request failed", FALLBACK))
                .startsWith("参考图里有清晰的真人面孔");
        assertThat(CustomerMessages.plain("OutputVideoSensitiveContentDetected: blocked", FALLBACK))
                .startsWith("画面或描述里可能有平台不允许的内容");
    }

    @Test
    void plainEnglishModerationSentenceIsTranslated() {
        String raw = "volc asset processing failed: The request failed because the input text may contain sensitive information. "
                + "Request ID: 2026112315332659743CFBB6C63468176_asset-20261123153327-6q5v6";
        assertThat(CustomerMessages.plain(raw, FALLBACK)).startsWith("画面或描述里可能有平台不允许的内容");
    }

    @Test
    void commonProviderFailuresAreTranslated() {
        assertThat(CustomerMessages.plain("供应商响应 HTTP 429: rate limit exceeded", FALLBACK)).isEqualTo("现在使用的人比较多，请过几分钟再试。");
        assertThat(CustomerMessages.plain("视频供应商在规定时间内未完成任务", FALLBACK)).isEqualTo("这次生成等了太久没有完成，请稍后重试。");
        assertThat(CustomerMessages.plain("供应商响应 HTTP 401: invalid api key", FALLBACK)).startsWith("生成服务暂时出了点问题");
        assertThat(CustomerMessages.plain("供应商响应 HTTP 502", FALLBACK)).isEqualTo("生成服务暂时不稳定，请稍后重试。");
    }

    @Test
    void unknownTechnicalErrorsFallBackAndPlainMessagesStay() {
        assertThat(CustomerMessages.plain("GenerationFailed: something odd", FALLBACK)).isEqualTo(FALLBACK);
        assertThat(CustomerMessages.plain("供应商已确认视频生成失败", FALLBACK)).isEqualTo(FALLBACK);
        assertThat(CustomerMessages.plain("视频生成超过最大处理时长", FALLBACK)).isEqualTo("视频生成超过最大处理时长");
        assertThat(CustomerMessages.plain("已取消本次图片生成", FALLBACK)).isEqualTo("已取消本次图片生成");
        assertThat(CustomerMessages.plain(null, FALLBACK)).isNull();
    }
}
