package com.wuyao.vimax.controller;

import com.wuyao.vimax.dto.ApiResponse;
import com.wuyao.vimax.dto.AuthorizeAssetRequest;
import com.wuyao.vimax.entity.AssetAuthorization;
import com.wuyao.vimax.service.AssetAuthorizationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 资产授权 Controller
 */
@RestController
@RequestMapping("/assets")
@RequiredArgsConstructor
@Slf4j
public class AssetAuthorizationController {

    private final AssetAuthorizationService authorizationService;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /**
     * 授权资产
     */
    @PostMapping("/{assetId}/authorizations")
    public ApiResponse<AssetAuthorization> authorizeAsset(
            @PathVariable Long assetId,
            @Valid @RequestBody AuthorizeAssetRequest request,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "1") Long userId) {

        log.info("授权资产：assetId={}, merchantId={}", assetId, request.getMerchantId());

        LocalDateTime expiresAt = null;
        if (request.getExpiresAt() != null && !request.getExpiresAt().isEmpty()) {
            expiresAt = LocalDateTime.parse(request.getExpiresAt(), FORMATTER);
        }

        AssetAuthorization authorization = authorizationService.authorizeAsset(
                assetId,
                request.getMerchantId(),
                request.getAuthorizationScope(),
                request.getScopeReference(),
                expiresAt,
                userId
        );

        return ApiResponse.success("资产授权成功", authorization);
    }

    /**
     * 查询资产的授权列表
     */
    @GetMapping("/{assetId}/authorizations")
    public ApiResponse<List<AssetAuthorization>> getAuthorizationsByAsset(@PathVariable Long assetId) {
        log.info("查询资产授权列表：assetId={}", assetId);

        List<AssetAuthorization> authorizations = authorizationService.getAuthorizationsByAsset(assetId);
        return ApiResponse.success(authorizations);
    }

    /**
     * 查询商家的授权列表
     */
    @GetMapping("/authorizations")
    public ApiResponse<List<AssetAuthorization>> getAuthorizationsByMerchant(
            @RequestParam Long merchantId) {

        log.info("查询商家授权列表：merchantId={}", merchantId);

        List<AssetAuthorization> authorizations = authorizationService.getAuthorizationsByMerchant(merchantId);
        return ApiResponse.success(authorizations);
    }

    /**
     * 撤销授权
     */
    @DeleteMapping("/authorizations/{authorizationId}")
    public ApiResponse<Void> revokeAuthorization(@PathVariable Long authorizationId) {
        log.info("撤销授权：authorizationId={}", authorizationId);

        authorizationService.revokeAuthorization(authorizationId);
        return ApiResponse.success("授权已撤销", null);
    }
}
