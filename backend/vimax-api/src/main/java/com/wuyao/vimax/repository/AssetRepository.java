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
     * 根据 code 查找资产
     */
    Optional<Asset> findByCode(String code);

    /**
     * 查询商家的资产列表
     */
    List<Asset> findByMerchantIdAndStatus(Long merchantId, String status);

    /**
     * 按类型查询资产
     */
    List<Asset> findByMerchantIdAndTypeAndStatus(Long merchantId, String type, String status);

    /**
     * 检查资产是否存在
     */
    boolean existsByCode(String code);
}
