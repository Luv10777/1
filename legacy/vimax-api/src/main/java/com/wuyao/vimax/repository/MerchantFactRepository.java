package com.wuyao.vimax.repository;

import com.wuyao.vimax.entity.MerchantFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商家事实 Repository
 */
@Repository
public interface MerchantFactRepository extends JpaRepository<MerchantFact, Long> {

    /**
     * 查询商家的所有有效事实
     */
    List<MerchantFact> findByMerchantIdAndStatus(Long merchantId, String status);

    /**
     * 查询商家在指定时间生效的事实
     */
    @Query("SELECT f FROM MerchantFact f WHERE f.merchantId = :merchantId " +
           "AND f.status = 'ACTIVE' " +
           "AND (f.expiresAt IS NULL OR f.expiresAt > :currentTime) " +
           "ORDER BY f.isCritical DESC, f.createdAt ASC")
    List<MerchantFact> findEffectiveFacts(Long merchantId, LocalDateTime currentTime);

    /**
     * 按类型查询商家事实
     */
    List<MerchantFact> findByMerchantIdAndFactTypeAndStatus(
            Long merchantId, String factType, String status);
}
