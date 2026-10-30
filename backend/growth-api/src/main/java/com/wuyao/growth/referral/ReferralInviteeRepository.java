package com.wuyao.growth.referral;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReferralInviteeRepository extends JpaRepository<ReferralInvitee, Long> {
    List<ReferralInvitee> findTop100ByTenantIdAndReferrerUserIdOrderByRegisteredAtDesc(Long tenantId, Long referrerUserId);

    long countByTenantIdAndReferrerUserId(Long tenantId, Long referrerUserId);
}
