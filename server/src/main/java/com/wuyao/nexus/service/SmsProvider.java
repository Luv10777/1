package com.wuyao.nexus.service;

public interface SmsProvider {
    /**
     * 发送验证码
     * @param phone 手机号
     * @param code 验证码
     */
    void sendVerificationCode(String phone, String code);
}
