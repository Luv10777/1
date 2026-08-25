package com.wuyao.nexus.repository;

import com.wuyao.nexus.entity.Asset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface AssetRepository extends JpaRepository<Asset, Long> {
    Page<Asset> findByTenantIdAndDeletedAtIsNull(Long tenantId, Pageable pageable);

    @Query("SELECT a FROM Asset a WHERE a.tenantId = :tenantId " +
           "AND (:type IS NULL OR a.type = :type) " +
           "AND (:category IS NULL OR a.category = :category) " +
           "AND a.deletedAt IS NULL")
    Page<Asset> findByFilters(@Param("tenantId") Long tenantId,
                               @Param("type") Asset.AssetType type,
                               @Param("category") String category,
                               Pageable pageable);

    Optional<Asset> findByIdAndTenantIdAndDeletedAtIsNull(Long id, Long tenantId);

    @Query("SELECT a FROM Asset a WHERE a.tenantId = :tenantId " +
           "AND a.status = 'AVAILABLE' " +
           "AND (a.licenseValidUntil IS NULL OR a.licenseValidUntil >= :today) " +
           "AND a.deletedAt IS NULL")
    Page<Asset> findAvailableAssets(@Param("tenantId") Long tenantId,
                                     @Param("today") LocalDate today,
                                     Pageable pageable);
}
