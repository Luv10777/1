package com.wuyao.growth.iam.service;

/** 换短信服务商只换实现类。 */
public interface SmsSender {
    void sendLoginCode(String phone, String code);
}
