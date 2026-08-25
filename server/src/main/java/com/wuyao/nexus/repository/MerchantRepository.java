package com.wuyao.nexus.repository;

import com.wuyao.nexus.entity.Merchant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MerchantRepository extends JpaRepository<Merchant, Long> {
    Page<Merchant> findByTenantIdAndDeletedAtIsNull(Long tenantId, Pageable pageable);
    Optional<Merchant> findByIdAndTenantIdAndDeletedAtIsNull(Long id, Long tenantId);
    Optional<Merchant> findByCode(String code);
}
