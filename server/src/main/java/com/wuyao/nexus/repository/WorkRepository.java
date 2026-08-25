package com.wuyao.nexus.repository;

import com.wuyao.nexus.entity.Work;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WorkRepository extends JpaRepository<Work, Long> {
    Page<Work> findByTenantIdAndDeletedAtIsNull(Long tenantId, Pageable pageable);

    @Query("SELECT w FROM Work w WHERE w.tenantId = :tenantId " +
           "AND (:reviewStatus IS NULL OR w.reviewStatus = :reviewStatus) " +
           "AND (:type IS NULL OR w.type = :type) " +
           "AND w.deletedAt IS NULL " +
           "ORDER BY w.createdAt DESC")
    Page<Work> findByFilters(@Param("tenantId") Long tenantId,
                              @Param("reviewStatus") Work.ReviewStatus reviewStatus,
                              @Param("type") Work.WorkType type,
                              Pageable pageable);

    Optional<Work> findByIdAndTenantIdAndDeletedAtIsNull(Long id, Long tenantId);

    Page<Work> findByReviewStatusAndDeletedAtIsNull(Work.ReviewStatus reviewStatus, Pageable pageable);
}
