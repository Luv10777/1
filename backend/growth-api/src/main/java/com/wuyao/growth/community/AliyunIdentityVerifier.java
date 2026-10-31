package com.wuyao.growth.community;

import com.aliyun.cloudauth20190307.Client;
import com.aliyun.cloudauth20190307.models.BankMetaVerifyRequest;
import com.aliyun.cloudauth20190307.models.Id2MetaVerifyRequest;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.Callable;

/** 阿里云实人认证「身份二要素核验」和「银行卡四要素核验」。只有明确的一致或不一致才算结论，其余一律算暂时无法核验；不记录姓名、号码、卡号或供应商原始报文。 */
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
        return call("身份核验", () -> {
            var response = client.id2MetaVerifyWithOptions(new Id2MetaVerifyRequest().setParamType("normal")
                    .setUserName(legalName).setIdentifyNum(idNumber), options());
            var body = response == null ? null : response.getBody();
            return new Answer(response == null ? null : response.getStatusCode(), body == null ? null : body.getCode(),
                    body == null || body.getResultObject() == null ? null : body.getResultObject().getBizCode(),
                    body == null ? null : body.getRequestId());
        });
    }

    /** 四要素按「卡号 + 姓名 + 身份证号 + 预留手机号」核验；查无记录（bizCode 3）也算不一致，请用户换一张卡。 */
    @Override
    public Result verifyBankCard(String legalName, String idNumber, String cardNumber, String mobile) {
        return call("银行卡核验", () -> {
            var response = client.bankMetaVerifyWithOptions(new BankMetaVerifyRequest().setParamType("normal")
                    .setProductType("BANK_CARD_4_META").setVerifyMode("VERIFY_BANK_CARD").setIdentityType("01")
                    .setUserName(legalName).setIdentifyNum(idNumber).setBankCard(cardNumber).setMobile(mobile), options());
            var body = response == null ? null : response.getBody();
            String bizCode = body == null || body.getResultObject() == null ? null : body.getResultObject().getBizCode();
            return new Answer(response == null ? null : response.getStatusCode(), body == null ? null : body.getCode(),
                    "3".equals(bizCode) ? "2" : bizCode, body == null ? null : body.getRequestId());
        });
    }

    private Result call(String label, Callable<Answer> request) {
        if (client == null) {
            log.warn("实名核验已选择阿里云，但 AccessKey 未配置");
            return Result.unavailable();
        }
        try {
            var answer = request.call();
            if (Integer.valueOf(200).equals(answer.httpStatus()) && "200".equals(answer.code())) {
                if ("1".equals(answer.bizCode())) return new Result(Outcome.MATCH, diagnosticValue(answer.requestId()));
                if ("2".equals(answer.bizCode())) return new Result(Outcome.MISMATCH, diagnosticValue(answer.requestId()));
            }
            log.warn("阿里云{}没有给出结论: httpStatus={} providerCode={} bizCode={} requestId={}", label, answer.httpStatus(),
                    diagnosticValue(answer.code()), diagnosticValue(answer.bizCode()), diagnosticValue(answer.requestId()));
        } catch (Exception e) {
            log.warn("阿里云{}调用失败，异常类型={}", label, e.getClass().getSimpleName());
        }
        return Result.unavailable();
    }

    /** 核验按次计费，超时的请求可能已被受理，禁止 SDK 自动重试。 */
    private static RuntimeOptions options() {
        return new RuntimeOptions().setConnectTimeout(3000).setReadTimeout(5000).setAutoretry(false);
    }

    private record Answer(Integer httpStatus, String code, String bizCode, String requestId) {}

    private static String diagnosticValue(String value) {
        return value != null && value.matches("[A-Za-z0-9_.-]{1,100}") ? value : "unavailable";
    }
}
