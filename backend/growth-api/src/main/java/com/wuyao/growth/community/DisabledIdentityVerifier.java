package com.wuyao.growth.community;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 没有配置实名核验服务时，提交实名、绑定银行卡会提示暂时不可用；实名不做人工审核。 */
@Component
@ConditionalOnProperty(name = "growth.identity.verifier", havingValue = "none", matchIfMissing = true)
public class DisabledIdentityVerifier implements IdentityVerifier {
    @Override
    public Result verify(String legalName, String idNumber) {
        return Result.unavailable();
    }

    @Override
    public Result verifyBankCard(String legalName, String idNumber, String cardNumber, String mobile) {
        return Result.unavailable();
    }
}
