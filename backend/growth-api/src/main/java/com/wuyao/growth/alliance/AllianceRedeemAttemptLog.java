package com.wuyao.growth.alliance;

import com.wuyao.growth.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 记下每次核销尝试。独立提交：核销失败抛错回滚时，这次失败也要留下来计数。 */
@Component
@RequiredArgsConstructor
class AllianceRedeemAttemptLog {
    private final AllianceRedeemAttemptRepository attempts;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long storeId, Long userId, boolean succeeded) {
        var attempt = new AllianceRedeemAttempt();
        attempt.setTenantId(TenantContext.require());
        attempt.setStoreId(storeId);
        attempt.setUserId(userId);
        attempt.setSucceeded(succeeded);
        attempts.save(attempt);
    }
}
