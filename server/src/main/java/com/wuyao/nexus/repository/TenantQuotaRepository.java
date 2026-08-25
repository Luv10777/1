package com.wuyao.nexus.repository;

import com.wuyao.nexus.entity.TenantQuota;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;

@Repository
public interface TenantQuotaRepository extends JpaRepository<TenantQuota, Long> {
    Optional<TenantQuota> findByTenantId(Long tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<TenantQuota> findByTenantIdForUpdate(Long tenantId);
}
