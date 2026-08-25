package com.wuyao.nexus.service.impl;

import com.wuyao.nexus.service.SmsProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@ConditionalOnProperty(name = "sms.provider", havingValue = "console", matchIfMissing = true)
public class ConsoleSmsProvider implements SmsProvider {

    @Override
    public void sendVerificationCode(String phone, String code) {
        log.info("=================================================");
        log.info("【开发环境】发送验证码");
        log.info("手机号: {}", phone);
        log.info("验证码: {}", code);
        log.info("=================================================");
    }
}
