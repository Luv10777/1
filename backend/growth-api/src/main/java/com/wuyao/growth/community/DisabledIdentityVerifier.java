package com.wuyao.growth.community;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 没有配置实名核验服务时，提交实名会提示暂时不可用；实名不做人工审核。 */
@Component
@ConditionalOnProperty(name = "growth.identity.verifier", havingValue = "none", matchIfMissing = true)
public class DisabledIdentityVerifier implements IdentityVerifier {
    @Override
    public Result verify(String legalName, String idNumber) {
        return Result.unavailable();
    }
}
