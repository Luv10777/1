package com.wuyao.nexus.repository;

import com.wuyao.nexus.entity.Store;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StoreRepository extends JpaRepository<Store, Long> {
    Page<Store> findByMerchantIdAndDeletedAtIsNull(Long merchantId, Pageable pageable);
    Optional<Store> findByIdAndTenantIdAndDeletedAtIsNull(Long id, Long tenantId);
}
