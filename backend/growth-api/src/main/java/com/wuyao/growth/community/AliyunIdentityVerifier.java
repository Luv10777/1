package com.wuyao.growth.community;

import com.aliyun.cloudauth20190307.Client;
import com.aliyun.cloudauth20190307.models.Id2MetaVerifyRequest;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 阿里云实人认证「身份二要素核验」。只有明确的一致或不一致才算结论，其余一律算暂时无法核验；不记录姓名、号码或供应商原始报文。 */
@Slf4j
@Component
@ConditionalOnProperty(name = "growth.identity.verifier", havingValue = "aliyun")
public class AliyunIdentityVerifier implements IdentityVerifier {
    private final Client client;

    @Autowired
    public AliyunIdentityVerifier(
            @Value("${growth.identity.aliyun.access-key-id:}") String accessKeyId,
            @Value("${growth.identity.aliyun.access-key-secret:}") String accessKeySecret,
            @Value("${growth.identity.aliyun.endpoint:cloudauth.cn-shanghai.aliyuncs.com}") String endpoint) throws Exception {
        this(StringUtils.hasText(accessKeyId) && StringUtils.hasText(accessKeySecret)
                ? new Client(new Config().setAccessKeyId(accessKeyId).setAccessKeySecret(accessKeySecret)
                    .setEndpoint(endpoint).setProtocol("https"))
                : null);
    }

    AliyunIdentityVerifier(Client client) {
        this.client = client;
    }

    @Override
    public Result verify(String legalName, String idNumber) {
        if (client == null) {
            log.warn("实名核验已选择阿里云，但 AccessKey 未配置");
            return Result.unavailable();
        }
        try {
            var request = new Id2MetaVerifyRequest().setParamType("normal").setUserName(legalName).setIdentifyNum(idNumber);
            // 核验按次计费，超时的请求可能已被受理，禁止 SDK 自动重试。
            var options = new RuntimeOptions().setConnectTimeout(3000).setReadTimeout(5000).setAutoretry(false);
            var response = client.id2MetaVerifyWithOptions(request, options);
            var body = response == null ? null : response.getBody();
            String bizCode = body == null || body.getResultObject() == null ? null : body.getResultObject().getBizCode();
            if (body != null && Integer.valueOf(200).equals(response.getStatusCode()) && "200".equals(body.getCode())) {
                if ("1".equals(bizCode)) return new Result(Outcome.MATCH, diagnosticValue(body.getRequestId()));
                if ("2".equals(bizCode)) return new Result(Outcome.MISMATCH, diagnosticValue(body.getRequestId()));
            }
            log.warn("阿里云身份核验没有给出结论: httpStatus={} providerCode={} bizCode={} requestId={}",
                    response == null ? null : response.getStatusCode(),
                    diagnosticValue(body == null ? null : body.getCode()), diagnosticValue(bizCode),
                    diagnosticValue(body == null ? null : body.getRequestId()));
        } catch (Exception e) {
            log.warn("阿里云身份核验调用失败，异常类型={}", e.getClass().getSimpleName());
        }
        return Result.unavailable();
    }

    private static String diagnosticValue(String value) {
        return value != null && value.matches("[A-Za-z0-9_.-]{1,100}") ? value : "unavailable";
    }
}
