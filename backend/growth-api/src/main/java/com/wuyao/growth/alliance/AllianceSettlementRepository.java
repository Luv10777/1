package com.wuyao.growth.alliance;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface AllianceSettlementRepository extends JpaRepository<AllianceSettlement, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AllianceSettlement s where s.voucherRef = :ref")
    Optional<AllianceSettlement> findForUpdate(@Param("ref") UUID ref);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AllianceSettlement s where s.id = :id")
    Optional<AllianceSettlement> findByIdForUpdate(@Param("id") Long id);

    Page<AllianceSettlement> findAllByTenantIdOrderByCreatedAtDescIdDesc(Long tenantId, Pageable pageable);

    List<AllianceSettlement> findAllByTenantIdAndStatusOrderByCreatedAtAsc(Long tenantId, String status);

    @Query("select coalesce(sum(s.settleAmountMinor), 0) from AllianceSettlement s where s.tenantId = :tenantId and s.status = :status")
    long sumAmount(@Param("tenantId") Long tenantId, @Param("status") String status);
}
