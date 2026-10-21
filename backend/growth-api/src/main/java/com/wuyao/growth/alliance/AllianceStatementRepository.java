package com.wuyao.growth.alliance;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

interface AllianceStatementRepository extends JpaRepository<AllianceStatement, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AllianceStatement s where s.id = :id")
    Optional<AllianceStatement> findForUpdate(@Param("id") Long id);

    boolean existsByTenantIdAndPeriodAndStatusNot(Long tenantId, String period, String status);

    List<AllianceStatement> findAllByTenantIdOrderByPeriodDescIdDesc(Long tenantId);

    @Query("select s.id from AllianceStatement s where s.tenantId = :tenantId and s.status = 'PENDING_CONFIRM' and s.confirmDueAt <= :now")
    List<Long> dueForAutoConfirm(@Param("tenantId") Long tenantId, @Param("now") Instant now);
}
