package com.wuyao.growth.alliance;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface AllianceVoucherRepository extends JpaRepository<AllianceVoucher, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from AllianceVoucher v where v.voucherRef = :ref")
    Optional<AllianceVoucher> findForUpdate(@Param("ref") UUID ref);

    Page<AllianceVoucher> findAllByTenantIdOrderByClaimedAtDesc(Long tenantId, Pageable pageable);

    long countByQuotaGrantIdAndSupplierTenantIdAndStatusIn(Long quotaGrantId, Long supplierTenantId, Collection<String> statuses);

    List<AllianceVoucher> findAllByTenantIdAndStatusOrderByDisputedAtAsc(Long tenantId, String status);

    long countByTenantIdAndStatus(Long tenantId, String status);

    @Query("select coalesce(sum(v.faceValueMinor), 0) from AllianceVoucher v where v.tenantId = :tenantId and v.status in :statuses")
    long sumFace(@Param("tenantId") Long tenantId, @Param("statuses") Collection<String> statuses);
}
