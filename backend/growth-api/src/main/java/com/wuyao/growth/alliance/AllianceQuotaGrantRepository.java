package com.wuyao.growth.alliance;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

interface AllianceQuotaGrantRepository extends JpaRepository<AllianceQuotaGrant, Long> {

    boolean existsByBillingOrderId(String billingOrderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select g from AllianceQuotaGrant g
            where g.tenantId = :tenantId and g.validFrom <= :now and g.validUntil > :now
            order by g.validFrom desc, g.id desc
            """)
    List<AllianceQuotaGrant> currentForUpdate(@Param("tenantId") Long tenantId, @Param("now") Instant now);

    @Query("""
            select g from AllianceQuotaGrant g
            where g.tenantId = :tenantId and g.validUntil > :now
            order by g.validFrom, g.id
            """)
    List<AllianceQuotaGrant> currentAndUpcoming(@Param("tenantId") Long tenantId, @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from AllianceQuotaGrant g where g.id = :id")
    Optional<AllianceQuotaGrant> findForUpdate(@Param("id") Long id);

    @Query("select coalesce(sum(g.amountMinor), 0) from AllianceQuotaGrant g where g.tenantId = :tenantId")
    long totalGranted(@Param("tenantId") Long tenantId);
}
