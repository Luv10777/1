package com.wuyao.vimax.repository;

import com.wuyao.vimax.entity.Asset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 资产 Repository
 */
@Repository
public interface AssetRepository extends JpaRepository<Asset, Long> {

    /**
     * 根据SHA256哈希查找（去重）
     */
    Optional<Asset> findBySha256Hash(String sha256Hash);

    /**
     * 根据 S3 Key 查找
     */
    Optional<Asset> findByS3Key(String s3Key);
}
