package com.wuyao.growth.community;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RealNameApplicationRepository extends JpaRepository<RealNameApplication, Long> {
    Optional<RealNameApplication> findByTenantIdAndUserId(Long tenantId, Long userId);
    Optional<RealNameApplication> findByIdAndTenantId(Long id, Long tenantId);
    long countByTenantIdAndStatus(Long tenantId, String status);

    java.util.List<RealNameApplication> findAllByTenantIdOrderBySubmittedAtDescIdDesc(Long tenantId);

}
