package com.wuyao.vimax.repository;

import com.wuyao.vimax.entity.MerchantFactSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 商家事实快照 Repository
 */
@Repository
public interface MerchantFactSnapshotRepository extends JpaRepository<MerchantFactSnapshot, Long> {

    /**
     * 根据哈希查找快照（去重）
     */
    Optional<MerchantFactSnapshot> findBySnapshotHash(String snapshotHash);

    /**
     * 查询商家的所有快照
     */
    List<MerchantFactSnapshot> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);

    /**
     * 检查快照是否存在
     */
    boolean existsBySnapshotHash(String snapshotHash);
}
