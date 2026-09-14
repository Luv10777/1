package com.wuyao.growth.iam.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 开发环境用：验证码打到控制台，不真发短信。
 * 生产必须把 growth.sms.provider 换成真实服务商，否则验证码会进日志。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "growth.sms.provider", havingValue = "console", matchIfMissing = true)
public class ConsoleSmsSender implements SmsSender {

    @Override
    public void sendLoginCode(String phone, String code) {
        log.warn("【开发模式】{} 的登录验证码是 {} —— 生产环境不会打印", phone, code);
    }
}
