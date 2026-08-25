package com.wuyao.nexus.repository;

import com.wuyao.nexus.entity.Brand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BrandRepository extends JpaRepository<Brand, Long> {
    Page<Brand> findByMerchantIdAndDeletedAtIsNull(Long merchantId, Pageable pageable);
    Optional<Brand> findByIdAndTenantIdAndDeletedAtIsNull(Long id, Long tenantId);
    Optional<Brand> findByCode(String code);
}
