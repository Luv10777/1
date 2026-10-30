package com.wuyao.growth.community;

import com.aliyun.cloudauth20190307.Client;
import com.aliyun.cloudauth20190307.models.Id2MetaVerifyRequest;
import com.aliyun.cloudauth20190307.models.Id2MetaVerifyResponse;
import com.aliyun.cloudauth20190307.models.Id2MetaVerifyResponseBody;
import com.aliyun.teautil.models.RuntimeOptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AliyunIdentityVerifierTest {
    private final Client client = mock(Client.class);
    private final AliyunIdentityVerifier verifier = new AliyunIdentityVerifier(client);

    @Test
    void sendsPlainNameAndNumberWithoutAutomaticRetry() throws Exception {
        when(client.id2MetaVerifyWithOptions(any(), any())).thenReturn(response(200, "200", "1"));
        var result = verifier.verify("张三", "11010519491231002X");

        var request = ArgumentCaptor.forClass(Id2MetaVerifyRequest.class);
        var runtime = ArgumentCaptor.forClass(RuntimeOptions.class);
        verify(client).id2MetaVerifyWithOptions(request.capture(), runtime.capture());
        assertThat(request.getValue().getParamType()).isEqualTo("normal");
        assertThat(request.getValue().getUserName()).isEqualTo("张三");
        assertThat(request.getValue().getIdentifyNum()).isEqualTo("11010519491231002X");
        assertThat(runtime.getValue().getAutoretry()).isFalse();
        assertThat(result).isEqualTo(new IdentityVerifier.Result(IdentityVerifier.Outcome.MATCH, "REQ-1"));
    }

    @Test
    void mismatchIsAConclusion() throws Exception {
        when(client.id2MetaVerifyWithOptions(any(), any())).thenReturn(response(200, "200", "2"));
        assertThat(verifier.verify("张三", "11010519491231002X").outcome()).isEqualTo(IdentityVerifier.Outcome.MISMATCH);
    }

    @Test
    void anythingElseIsUnavailableNeverAMatch() throws Exception {
        for (var response : new Id2MetaVerifyResponse[]{response(200, "200", "3"), response(200, "200", null),
                response(200, "401", "1"), response(500, "200", "1"), new Id2MetaVerifyResponse(), null}) {
            when(client.id2MetaVerifyWithOptions(any(), any())).thenReturn(response);
            assertThat(verifier.verify("张三", "11010519491231002X").outcome()).isEqualTo(IdentityVerifier.Outcome.UNAVAILABLE);
        }
    }

    @Test
    void timeoutIsUnavailableAndCalledOnce() throws Exception {
        when(client.id2MetaVerifyWithOptions(any(), any())).thenThrow(new SocketTimeoutException("sensitive"));
        assertThat(verifier.verify("张三", "11010519491231002X").outcome()).isEqualTo(IdentityVerifier.Outcome.UNAVAILABLE);
        verify(client, times(1)).id2MetaVerifyWithOptions(any(), any());
    }

    @Test
    void missingCredentialsFallBackToManualReview() {
        assertThat(new AliyunIdentityVerifier(null).verify("张三", "11010519491231002X").outcome())
                .isEqualTo(IdentityVerifier.Outcome.UNAVAILABLE);
    }

    private static Id2MetaVerifyResponse response(int status, String code, String bizCode) {
        var body = new Id2MetaVerifyResponseBody().setCode(code).setRequestId("REQ-1")
                .setResultObject(new Id2MetaVerifyResponseBody.Id2MetaVerifyResponseBodyResultObject().setBizCode(bizCode));
        return new Id2MetaVerifyResponse().setStatusCode(status).setBody(body);
    }
}
