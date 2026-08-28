package com.wuyao.vimax.repository;

import com.wuyao.vimax.entity.AssetAuthorization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 资产授权 Repository
 */
@Repository
public interface AssetAuthorizationRepository extends JpaRepository<AssetAuthorization, Long> {

    /**
     * 查询资产的所有授权
     */
    List<AssetAuthorization> findByAssetIdAndStatus(Long assetId, String status);

    /**
     * 查询商家的授权列表
     */
    List<AssetAuthorization> findByMerchantIdAndStatus(Long merchantId, String status);

    /**
     * 查询特定商家和资产的授权
     */
    List<AssetAuthorization> findByMerchantIdAndAssetIdAndStatus(
            Long merchantId, Long assetId, String status);
}
