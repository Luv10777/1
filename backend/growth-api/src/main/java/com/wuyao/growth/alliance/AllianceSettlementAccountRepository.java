package com.wuyao.growth.alliance;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

interface AllianceSettlementAccountRepository extends JpaRepository<AllianceSettlementAccount, Long> {

    Optional<AllianceSettlementAccount> findByTenantId(Long tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AllianceSettlementAccount a where a.tenantId = :tenantId")
    Optional<AllianceSettlementAccount> findForUpdate(@Param("tenantId") Long tenantId);
}
