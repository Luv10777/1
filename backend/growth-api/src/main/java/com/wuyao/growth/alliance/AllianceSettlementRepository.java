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

    /** 可以进对账单的：未结算、没冻结、申诉期在截止时间前已满、还没进过对账单。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from AllianceSettlement s
            where s.tenantId = :tenantId and s.status = 'HOLDING' and s.frozen = false
              and s.statementId is null and s.payableAfter < :cutoff
            order by s.payableAfter, s.id
            """)
    List<AllianceSettlement> eligibleForStatement(@Param("tenantId") Long tenantId, @Param("cutoff") java.time.Instant cutoff);

    List<AllianceSettlement> findAllByStatementIdOrderByCreatedAtAscIdAsc(Long statementId);

    Page<AllianceSettlement> findAllByTenantIdOrderByCreatedAtDescIdDesc(Long tenantId, Pageable pageable);

    List<AllianceSettlement> findAllByTenantIdAndStatusOrderByCreatedAtAsc(Long tenantId, String status);

    @Query("select coalesce(sum(s.settleAmountMinor), 0) from AllianceSettlement s where s.tenantId = :tenantId and s.status = :status")
    long sumAmount(@Param("tenantId") Long tenantId, @Param("status") String status);
}
