package com.wuyao.vimax.repository;

import com.wuyao.vimax.entity.MerchantFactSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * MerchantFactSnapshot Repository
 */
@Repository
public interface MerchantFactSnapshotRepository extends JpaRepository<MerchantFactSnapshot, Long> {

    /**
     * 根据快照代码查找
     */
    Optional<MerchantFactSnapshot> findBySnapshotCode(String snapshotCode);

    /**
     * 根据商家ID查找最新快照
     */
    Optional<MerchantFactSnapshot> findFirstByMerchantIdOrderByCreatedAtDesc(Long merchantId);
}
