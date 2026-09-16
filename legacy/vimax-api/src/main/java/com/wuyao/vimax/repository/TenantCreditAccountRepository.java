package com.wuyao.vimax.repository;

import com.wuyao.vimax.entity.TenantCreditAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TenantCreditAccountRepository extends JpaRepository<TenantCreditAccount, Long> {
    Optional<TenantCreditAccount> findByTenantId(Long tenantId);
}
