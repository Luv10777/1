package com.wuyao.vimax.repository;

import com.wuyao.vimax.entity.CreditTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CreditTransactionRepository extends JpaRepository<CreditTransaction, Long> {
    List<CreditTransaction> findByTenantIdOrderByTransactionTimeDesc(Long tenantId);
}
