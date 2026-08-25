package com.wuyao.nexus.repository;

import com.wuyao.nexus.entity.Knowledge;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface KnowledgeRepository extends JpaRepository<Knowledge, Long> {
    Page<Knowledge> findByTenantIdAndDeletedAtIsNull(Long tenantId, Pageable pageable);
    Page<Knowledge> findByMerchantIdAndStatusAndVerifiedAndDeletedAtIsNull(
            Long merchantId, Knowledge.KnowledgeStatus status, Boolean verified, Pageable pageable);
    Optional<Knowledge> findByIdAndTenantIdAndDeletedAtIsNull(Long id, Long tenantId);
}
