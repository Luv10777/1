package com.wuyao.growth.referral;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReferralCommissionRepository extends JpaRepository<ReferralCommission, Long> {
    boolean existsByOrderId(String orderId);

    List<ReferralCommission> findTop100ByTenantIdAndReferrerUserIdOrderByCreatedAtDesc(Long tenantId, Long referrerUserId);

    List<ReferralCommission> findTop2000ByTenantIdOrderByCreatedAtDesc(Long tenantId);

    @Query("select coalesce(sum(c.amountMinor), 0) from ReferralCommission c where c.tenantId = :tenantId "
            + "and c.referrerUserId = :userId and c.status = 'PENDING' and c.availableAt > :now")
    long sumFrozen(@Param("tenantId") Long tenantId, @Param("userId") Long userId, @Param("now") Instant now);

    @Query("select coalesce(sum(c.amountMinor), 0) from ReferralCommission c where c.tenantId = :tenantId "
            + "and c.referrerUserId = :userId and c.status = 'PENDING' and c.availableAt <= :now and c.withdrawalId is null")
    long sumAvailable(@Param("tenantId") Long tenantId, @Param("userId") Long userId, @Param("now") Instant now);

    /** 已经打款后又作废、还没被提现单扣回的返利，就是欠款。 */
    @Query("select coalesce(sum(c.amountMinor), 0) from ReferralCommission c where c.tenantId = :tenantId "
            + "and c.referrerUserId = :userId and c.status = 'VOIDED' and c.withdrawnAt is not null and c.clawbackWithdrawalId is null")
    long sumOwed(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ReferralCommission c where c.tenantId = :tenantId and c.referrerUserId = :userId "
            + "and c.status = 'PENDING' and c.availableAt <= :now and c.withdrawalId is null order by c.id")
    List<ReferralCommission> lockAvailable(@Param("tenantId") Long tenantId, @Param("userId") Long userId, @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ReferralCommission c where c.tenantId = :tenantId and c.referrerUserId = :userId "
            + "and c.status = 'VOIDED' and c.withdrawnAt is not null and c.clawbackWithdrawalId is null order by c.id")
    List<ReferralCommission> lockOwed(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ReferralCommission c where c.id = :id and c.tenantId = :tenantId")
    Optional<ReferralCommission> findForUpdate(@Param("id") Long id, @Param("tenantId") Long tenantId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ReferralCommission c set c.withdrawalId = null where c.tenantId = :tenantId and c.withdrawalId = :withdrawalId")
    int releaseWithdrawal(@Param("tenantId") Long tenantId, @Param("withdrawalId") Long withdrawalId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ReferralCommission c set c.clawbackWithdrawalId = null where c.tenantId = :tenantId and c.clawbackWithdrawalId = :withdrawalId")
    int releaseClawback(@Param("tenantId") Long tenantId, @Param("withdrawalId") Long withdrawalId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ReferralCommission c set c.withdrawnAt = :paidAt where c.tenantId = :tenantId and c.withdrawalId = :withdrawalId")
    int markWithdrawn(@Param("tenantId") Long tenantId, @Param("withdrawalId") Long withdrawalId, @Param("paidAt") Instant paidAt);
}
