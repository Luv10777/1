package com.wuyao.nexus.controller;

import com.wuyao.nexus.dto.ApiResponse;
import com.wuyao.nexus.dto.AssetRequest;
import com.wuyao.nexus.dto.AssetResponse;
import com.wuyao.nexus.service.AssetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;

    @GetMapping
    public ApiResponse<Page<AssetResponse>> list(
            @RequestAttribute("tenantId") Long tenantId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String category,
            Pageable pageable) {
        Page<AssetResponse> assets = assetService.list(tenantId, type, category, pageable);
        return ApiResponse.success(assets);
    }

    @GetMapping("/{id}")
    public ApiResponse<AssetResponse> get(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId) {
        AssetResponse asset = assetService.get(id, tenantId);
        return ApiResponse.success(asset);
    }

    @PostMapping
    public ApiResponse<AssetResponse> create(
            @RequestAttribute("tenantId") Long tenantId,
            @RequestAttribute("userId") Long userId,
            @Valid @RequestBody AssetRequest request) {
        AssetResponse asset = assetService.create(tenantId, userId, request);
        return ApiResponse.success(asset, "素材创建成功");
    }

    @PutMapping("/{id}")
    public ApiResponse<AssetResponse> update(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId,
            @Valid @RequestBody AssetRequest request) {
        AssetResponse asset = assetService.update(id, tenantId, request);
        return ApiResponse.success(asset, "素材更新成功");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId) {
        assetService.delete(id, tenantId);
        return ApiResponse.success(null, "素材删除成功");
    }

    @PostMapping("/upload-url")
    public ApiResponse<Map<String, String>> requestUploadUrl(
            @RequestAttribute("tenantId") Long tenantId,
            @RequestBody Map<String, String> request) {
        String fileName = request.get("fileName");
        String mimeType = request.get("mimeType");
        String uploadUrl = assetService.requestUploadUrl(tenantId, fileName, mimeType);
        return ApiResponse.success(Map.of("uploadUrl", uploadUrl));
    }

    @PostMapping("/{id}/confirm")
    public ApiResponse<Void> confirmUpload(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId,
            @RequestBody Map<String, String> request) {
        String fileUrl = request.get("fileUrl");
        assetService.confirmUpload(id, tenantId, fileUrl);
        return ApiResponse.success(null, "上传确认成功");
    }
}
