package com.wuyao.growth.referral;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 跨商户的写入。数据库连接在事务开始时绑定商户，所以每一步都在调用方 TenantContext.runAs 切到目标商户后
 * 新开事务；重复执行只会得到同一条记录。
 */
@Component
@RequiredArgsConstructor
class ReferralStore {
    private final ReferralAttributionRepository attributions;
    private final ReferralInviteeRepository invitees;
    private final ReferralCommissionRepository commissions;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAttribution(Long inviteeTenantId, Long referrerUserId, Long referrerTenantId, String code) {
        if (attributions.existsById(inviteeTenantId)) return;
        var row = new ReferralAttribution();
        row.setTenantId(inviteeTenantId);
        row.setReferrerUserId(referrerUserId);
        row.setReferrerTenantId(referrerTenantId);
        row.setCode(code);
        attributions.save(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveInvitee(Long referrerTenantId, Long referrerUserId, Long inviteeTenantId, String phoneMasked) {
        if (invitees.existsById(inviteeTenantId)) return;
        var row = new ReferralInvitee();
        row.setInviteeTenantId(inviteeTenantId);
        row.setTenantId(referrerTenantId);
        row.setReferrerUserId(referrerUserId);
        row.setInviteePhoneMasked(phoneMasked);
        invitees.save(row);
    }

    /** 返回 false 表示这笔充值已经记过。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean saveCommission(Long referrerTenantId, Long referrerUserId, Long inviteeTenantId, String orderId,
                                  String orderKind, long paidMinor, int rateBp, Instant availableAt) {
        if (commissions.existsByOrderId(orderId)) return false;
        var row = new ReferralCommission();
        row.setTenantId(referrerTenantId);
        row.setReferrerUserId(referrerUserId);
        row.setInviteeTenantId(inviteeTenantId);
        row.setOrderId(orderId);
        row.setOrderKind(orderKind);
        row.setPaidMinor(paidMinor);
        row.setRateBp(rateBp);
        row.setAmountMinor(paidMinor * rateBp / 10_000);
        row.setAvailableAt(availableAt);
        commissions.save(row);
        return true;
    }
}
