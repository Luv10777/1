package com.wuyao.growth.community;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RealNameApplicationRepository extends JpaRepository<RealNameApplication, Long> {
    @Query("select r.userId from RealNameApplication r where r.id = :id and r.tenantId = :tenantId")
    Optional<Long> userIdForReview(@Param("id") Long id, @Param("tenantId") Long tenantId);
    Optional<RealNameApplication> findByTenantIdAndUserId(Long tenantId, Long userId);
    Optional<RealNameApplication> findByIdAndTenantId(Long id, Long tenantId);
    long countByTenantIdAndStatus(Long tenantId, String status);

    java.util.List<RealNameApplication> findAllByTenantIdOrderBySubmittedAtDescIdDesc(Long tenantId);

}
