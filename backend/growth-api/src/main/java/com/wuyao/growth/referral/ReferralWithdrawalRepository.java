package com.wuyao.growth.referral;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReferralWithdrawalRepository extends JpaRepository<ReferralWithdrawal, Long> {
    List<ReferralWithdrawal> findTop20ByTenantIdAndUserIdOrderByCreatedAtDesc(Long tenantId, Long userId);

    List<ReferralWithdrawal> findTop2000ByTenantIdOrderByCreatedAtDesc(Long tenantId);

    boolean existsByTenantIdAndUserIdAndStatus(Long tenantId, Long userId, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from ReferralWithdrawal w where w.id = :id and w.tenantId = :tenantId")
    Optional<ReferralWithdrawal> findForUpdate(@Param("id") Long id, @Param("tenantId") Long tenantId);
}
