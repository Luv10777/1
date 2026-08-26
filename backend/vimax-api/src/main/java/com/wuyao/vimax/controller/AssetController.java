package com.wuyao.vimax.controller;

import com.wuyao.vimax.dto.ApiResponse;
import com.wuyao.vimax.dto.CreateAssetRequest;
import com.wuyao.vimax.dto.GetUploadUrlRequest;
import com.wuyao.vimax.dto.UploadUrlResponse;
import com.wuyao.vimax.entity.Asset;
import com.wuyao.vimax.service.AssetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 资产管理 Controller
 */
@RestController
@RequestMapping("/assets")
@RequiredArgsConstructor
@Slf4j
public class AssetController {

    private final AssetService assetService;

    /**
     * 获取预签名上传 URL
     */
    @PostMapping("/upload-url")
    public ApiResponse<UploadUrlResponse> getUploadUrl(
            @Valid @RequestBody GetUploadUrlRequest request,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "1") Long userId) {

        log.info("收到上传 URL 请求：fileName={}, fileType={}", request.getFileName(), request.getFileType());

        UploadUrlResponse response = assetService.getUploadUrl(request, userId);
        return ApiResponse.success("上传 URL 生成成功", response);
    }

    /**
     * 创建资产记录
     */
    @PostMapping
    public ApiResponse<Asset> createAsset(
            @Valid @RequestBody CreateAssetRequest request,
            @RequestParam String fileType,
            @RequestParam String mimeType,
            @RequestParam Long fileSize,
            @RequestParam(required = false) Long merchantId,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "1") Long userId) {

        log.info("创建资产记录：assetCode={}, name={}", request.getAssetCode(), request.getName());

        Asset asset = assetService.createAsset(
                request.getAssetCode(),
                request.getName(),
                fileType,
                mimeType,
                fileSize,
                merchantId,
                userId
        );

        return ApiResponse.success("资产创建成功", asset);
    }

    /**
     * 查询资产列表
     */
    @GetMapping
    public ApiResponse<List<Asset>> getAssets(
            @RequestParam(required = false) Long merchantId,
            @RequestParam(required = false) String type) {

        log.info("查询资产列表：merchantId={}, type={}", merchantId, type);

        List<Asset> assets = assetService.getAssetsByMerchant(merchantId, type);
        return ApiResponse.success(assets);
    }

    /**
     * 删除资产
     */
    @DeleteMapping("/{assetId}")
    public ApiResponse<Void> deleteAsset(@PathVariable Long assetId) {
        log.info("删除资产：assetId={}", assetId);

        assetService.deleteAsset(assetId);
        return ApiResponse.success("资产删除成功", null);
    }
}
