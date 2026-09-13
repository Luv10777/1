package com.wuyao.vimax.service;

import com.wuyao.vimax.entity.AssetAuthorization;
import com.wuyao.vimax.repository.AssetAuthorizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 资产授权服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssetAuthorizationService {

    private final AssetAuthorizationRepository authorizationRepository;

    /**
     * 授权资产给商家
     */
    @Transactional
    public AssetAuthorization authorizeAsset(Long assetId, Long merchantId,
                                             String scope, String scopeReference,
                                             LocalDateTime expiresAt, Long authorizedBy) {
        log.info("授权资产：assetId={}, merchantId={}, scope={}", assetId, merchantId, scope);

        AssetAuthorization authorization = new AssetAuthorization();
        authorization.setAssetId(assetId);
        authorization.setMerchantId(merchantId);
        authorization.setAuthorizationScope(scope);
        authorization.setScopeReference(scopeReference);
        authorization.setExpiresAt(expiresAt);
        authorization.setAuthorizedBy(authorizedBy);
        authorization.setAuthorizedAt(LocalDateTime.now());

        AssetAuthorization saved = authorizationRepository.save(authorization);
        log.info("资产授权成功：authorizationId={}", saved.getId());

        return saved;
    }

    /**
     * 查询资产的授权列表
     */
    public List<AssetAuthorization> getAuthorizationsByAsset(Long assetId) {
        return authorizationRepository.findByAssetIdAndStatus(assetId, "ACTIVE");
    }

    /**
     * 查询商家的授权列表
     */
    public List<AssetAuthorization> getAuthorizationsByMerchant(Long merchantId) {
        return authorizationRepository.findByMerchantIdAndStatus(merchantId, "ACTIVE");
    }

    /**
     * 撤销授权
     */
    @Transactional
    public void revokeAuthorization(Long authorizationId) {
        AssetAuthorization authorization = authorizationRepository.findById(authorizationId)
                .orElseThrow(() -> new IllegalArgumentException("授权不存在: " + authorizationId));

        authorization.setStatus("REVOKED");
        authorizationRepository.save(authorization);

        log.info("授权已撤销：authorizationId={}", authorizationId);
    }

    /**
     * 检查商家是否有资产授权
     */
    public boolean hasAuthorization(Long merchantId, Long assetId) {
        List<AssetAuthorization> authorizations =
                authorizationRepository.findByMerchantIdAndAssetIdAndStatus(
                        merchantId, assetId, "ACTIVE");

        if (authorizations.isEmpty()) {
            return false;
        }

        // 检查是否过期
        LocalDateTime now = LocalDateTime.now();
        return authorizations.stream()
                .anyMatch(auth -> auth.getExpiresAt() == null || auth.getExpiresAt().isAfter(now));
    }
}
