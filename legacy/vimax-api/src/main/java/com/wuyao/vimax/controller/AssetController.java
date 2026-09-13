package com.wuyao.vimax.controller;

import com.wuyao.vimax.dto.ApiResponse;
import com.wuyao.vimax.dto.GetUploadUrlRequest;
import com.wuyao.vimax.dto.ConfirmUploadRequest;
import com.wuyao.vimax.dto.UploadUrlResponse;
import com.wuyao.vimax.entity.Asset;
import com.wuyao.vimax.service.AssetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 资产管理 Controller
 *
 * Phase 7: 前端集成API
 */
@RestController
@RequestMapping("/assets")
@RequiredArgsConstructor
@Slf4j
public class AssetController {

    private final AssetService assetService;

    /**
     * 获取上传URL
     */
    @PostMapping("/upload-url")
    public ApiResponse<UploadUrlResponse> getUploadUrl(
            @RequestBody GetUploadUrlRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId) {

        log.info("获取上传URL: fileName={}", request.getFileName());

        UploadUrlResponse response = assetService.getUploadUrl(request, userId);
        return ApiResponse.success(response);
    }

    /**
     * 确认上传
     */
    @PostMapping("/confirm-upload")
    public ApiResponse<Asset> confirmUpload(
            @RequestBody ConfirmUploadRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId) {

        log.info("确认上传: objectKey={}", request.getObjectKey());

        Asset asset = assetService.confirmUpload(
            request.getObjectKey(),
            request.getAssetType(),
            request.getAssetCategory(),
            request.getFileName(),
            userId
        );

        return ApiResponse.success(asset);
    }

    /**
     * 获取下载URL
     */
    @GetMapping("/{assetId}/download-url")
    public ApiResponse<String> getDownloadUrl(@PathVariable Long assetId) {
        log.info("获取下载URL: assetId={}", assetId);

        String downloadUrl = assetService.getAssetDownloadUrl(assetId);
        return ApiResponse.success(downloadUrl);
    }

    /**
     * 删除资产
     */
    @DeleteMapping("/{assetId}")
    public ApiResponse<String> deleteAsset(@PathVariable Long assetId) {
        log.info("删除资产: assetId={}", assetId);

        assetService.deleteAsset(assetId);
        return ApiResponse.success("资产已删除");
    }

    /**
     * 获取资产列表
     */
    @GetMapping
    public ApiResponse<List<Asset>> listAssets(
            @RequestParam(required = false) String assetType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        log.info("获取资产列表: type={}, page={}, size={}", assetType, page, size);

        // TODO: 实现分页查询
        List<Asset> assets = List.of(); // 临时返回空列表
        return ApiResponse.success(assets);
    }
}
