package com.wuyao.growth.community;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MerchantApplicationRepository extends JpaRepository<MerchantApplication, Long> {
    List<MerchantApplication> findAllByOrderByCreatedAtDescIdDesc();

    List<MerchantApplication> findAllByTenantIdOrderByCreatedAtDescIdDesc(Long tenantId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    java.util.Optional<MerchantApplication> findByIdAndTenantId(Long id, Long tenantId);
}
