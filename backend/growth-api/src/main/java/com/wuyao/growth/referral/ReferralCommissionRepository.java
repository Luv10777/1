package com.wuyao.growth.referral;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ReferralCommissionRepository extends JpaRepository<ReferralCommission, Long> {
    boolean existsByOrderId(String orderId);

    List<ReferralCommission> findTop100ByTenantIdAndReferrerUserIdOrderByCreatedAtDesc(Long tenantId, Long referrerUserId);

    @Query("select coalesce(sum(c.amountMinor), 0) from ReferralCommission c where c.tenantId = :tenantId "
            + "and c.referrerUserId = :userId and c.status = 'PENDING' and c.availableAt > :now")
    long sumFrozen(@Param("tenantId") Long tenantId, @Param("userId") Long userId, @Param("now") Instant now);

    @Query("select coalesce(sum(c.amountMinor), 0) from ReferralCommission c where c.tenantId = :tenantId "
            + "and c.referrerUserId = :userId and c.status = 'PENDING' and c.availableAt <= :now")
    long sumAvailable(@Param("tenantId") Long tenantId, @Param("userId") Long userId, @Param("now") Instant now);
}
