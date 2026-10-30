package com.wuyao.growth.community;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 未接入权威核验时，所有实名申请都进入人工审核。 */
@Component
@ConditionalOnProperty(name = "growth.identity.verifier", havingValue = "manual", matchIfMissing = true)
public class ManualIdentityVerifier implements IdentityVerifier {
    @Override
    public Result verify(String legalName, String idNumber) {
        return Result.unavailable();
    }
}
