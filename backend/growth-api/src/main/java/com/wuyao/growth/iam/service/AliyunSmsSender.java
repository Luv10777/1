package com.wuyao.growth.iam.service;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/** 只把阿里云明确受理的请求视为发送成功，不记录凭证、手机号或验证码。 */
@Slf4j
@Component
@ConditionalOnProperty(name = "growth.sms.provider", havingValue = "aliyun")
public class AliyunSmsSender implements SmsSender {
    private final Client client;
    private final String signName;
    private final String templateCode;
    private final ObjectMapper json;

    @Autowired
    public AliyunSmsSender(
            @Value("${growth.sms.aliyun.access-key-id:}") String accessKeyId,
            @Value("${growth.sms.aliyun.access-key-secret:}") String accessKeySecret,
            @Value("${growth.sms.aliyun.sign-name:}") String signName,
            @Value("${growth.sms.aliyun.template-code:}") String templateCode,
            ObjectMapper json) throws Exception {
        this(StringUtils.hasText(accessKeyId) && StringUtils.hasText(accessKeySecret)
                ? new Client(new Config().setAccessKeyId(accessKeyId).setAccessKeySecret(accessKeySecret)
                    .setEndpoint("dysmsapi.aliyuncs.com").setProtocol("https"))
                : null, signName, templateCode, json);
    }

    AliyunSmsSender(Client client, String signName, String templateCode, ObjectMapper json) {
        this.client = client;
        this.signName = signName;
        this.templateCode = templateCode;
        this.json = json;
    }

    @Override
    public void sendLoginCode(String phone, String code) {
        if (client == null || !StringUtils.hasText(signName) || !StringUtils.hasText(templateCode)) {
            throw BizException.of(ErrorCode.SMS_NOT_CONFIGURED, "短信服务尚未配置完成，请联系管理员");
        }
        try {
            var request = new SendSmsRequest().setPhoneNumbers(phone).setSignName(signName)
                    .setTemplateCode(templateCode)
                    .setTemplateParam(json.writeValueAsString(Map.of("code", code)));
            // 超时结果可能已被供应商受理，禁止 SDK 自动重试以免重复发送。
            var options = new RuntimeOptions().setConnectTimeout(3000).setReadTimeout(5000)
                    .setAutoretry(false);
            var response = client.sendSmsWithOptions(request, options);
            if (response == null || response.getStatusCode() == null || response.getStatusCode() != 200
                    || response.getBody() == null || !"OK".equals(response.getBody().getCode())) {
                // 不输出供应商原始报文，其中可能包含手机号或请求参数。
                var body = response == null ? null : response.getBody();
                log.warn("阿里云未受理验证码短信请求: httpStatus={} providerCode={} requestId={}",
                        response == null ? null : response.getStatusCode(),
                        diagnosticValue(body == null ? null : body.getCode()),
                        diagnosticValue(body == null ? null : body.getRequestId()));
                throw BizException.of(ErrorCode.SMS_SEND_FAILED, "短信发送失败，请稍后重试或联系管理员");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("阿里云短信调用失败，异常类型={}", e.getClass().getSimpleName());
            throw BizException.of(ErrorCode.SMS_SEND_FAILED, "短信发送暂未成功，请稍后重试");
        }
    }

    private static String diagnosticValue(String value) {
        return value != null && value.matches("[A-Za-z0-9_.-]{1,100}") ? value : "unavailable";
    }
}
