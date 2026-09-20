package com.wuyao.growth.iam.service;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.dysmsapi20170525.models.SendSmsResponseBody;
import com.aliyun.teautil.models.RuntimeOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class AliyunSmsSenderTest {
    private final Client client = mock(Client.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AliyunSmsSender sender = new AliyunSmsSender(client, "一方志科技", "SMS_TEST", json);

    @Test
    void sendsTemplateWithLeadingZeroAndNoAutomaticRetry() throws Exception {
        when(client.sendSmsWithOptions(any(), any())).thenReturn(response("OK"));
        sender.sendLoginCode("13800000001", "012345");

        var request = ArgumentCaptor.forClass(SendSmsRequest.class);
        var runtime = ArgumentCaptor.forClass(RuntimeOptions.class);
        verify(client).sendSmsWithOptions(request.capture(), runtime.capture());
        assertThat(request.getValue().getPhoneNumbers()).isEqualTo("13800000001");
        assertThat(request.getValue().getSignName()).isEqualTo("一方志科技");
        assertThat(request.getValue().getTemplateCode()).isEqualTo("SMS_TEST");
        assertThat(json.readTree(request.getValue().getTemplateParam()).get("code").asText()).isEqualTo("012345");
        assertThat(runtime.getValue().getAutoretry()).isFalse();
        assertThat(runtime.getValue().getConnectTimeout()).isEqualTo(3000);
        assertThat(runtime.getValue().getReadTimeout()).isEqualTo(5000);
        assertThat(sender.developmentMode()).isFalse();
    }

    @Test
    void providerRejectionOrMalformedResponseNeverLooksSuccessful() throws Exception {
        for (var response : new SendSmsResponse[]{response("isv.BUSINESS_LIMIT_CONTROL"),
                response("isv.SMS_SIGNATURE_ILLEGAL"), response(null), new SendSmsResponse(),
                response("OK").setStatusCode(500), null}) {
            when(client.sendSmsWithOptions(any(), any())).thenReturn(response);
            assertThatThrownBy(() -> sender.sendLoginCode("13800000001", "123456"))
                    .isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SMS_SEND_FAILED));
        }
    }

    @Test
    void timeoutReturnsSafeErrorWithoutRetryOrLeakingProviderMessage() throws Exception {
        when(client.sendSmsWithOptions(any(), any())).thenThrow(new SocketTimeoutException("sensitive-request"));
        assertThatThrownBy(() -> sender.sendLoginCode("13800000001", "123456"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SMS_SEND_FAILED))
                .hasMessageNotContaining("sensitive-request");
        verify(client, times(1)).sendSmsWithOptions(any(), any());
    }

    @Test
    void missingCredentialsSignatureOrTemplateFailsBeforeCallingProvider() {
        for (var incomplete : new AliyunSmsSender[]{new AliyunSmsSender(null, "sign", "SMS_TEST", json),
                new AliyunSmsSender(client, " ", "SMS_TEST", json),
                new AliyunSmsSender(client, "sign", "", json)}) {
            assertThatThrownBy(() -> incomplete.sendLoginCode("13800000001", "123456"))
                    .isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SMS_NOT_CONFIGURED));
        }
        verifyNoInteractions(client);
    }

    @Test
    void aliyunSelectionDoesNotFallBackToConsoleWhenUnconfigured() {
        new ApplicationContextRunner().withUserConfiguration(AliyunSmsSender.class, ConsoleSmsSender.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("growth.sms.provider=aliyun")
                .run(context -> {
                    assertThat(context).hasSingleBean(SmsSender.class);
                    assertThat(context.getBean(SmsSender.class)).isInstanceOf(AliyunSmsSender.class);
                    assertThatThrownBy(() -> context.getBean(SmsSender.class).sendLoginCode("13800000001", "123456"))
                            .isInstanceOfSatisfying(BizException.class,
                                    e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SMS_NOT_CONFIGURED));
                });
    }

    private SendSmsResponse response(String code) {
        return new SendSmsResponse().setStatusCode(200).setBody(new SendSmsResponseBody().setCode(code));
    }

    @Test
    void rejectionLogsDiagnosticIdentifiersWithoutRequestOrMessage(CapturedOutput output) throws Exception {
        var rejected = response("isv.SMS_SIGNATURE_ILLEGAL");
        rejected.getBody().setRequestId("request-123").setMessage("private-provider-message");
        when(client.sendSmsWithOptions(any(), any())).thenReturn(rejected);
        assertThatThrownBy(() -> sender.sendLoginCode("13800000001", "012345"))
                .isInstanceOf(BizException.class);
        assertThat(output.getOut()).contains("isv.SMS_SIGNATURE_ILLEGAL", "request-123")
                .doesNotContain("13800000001", "012345", "private-provider-message");
    }
}
