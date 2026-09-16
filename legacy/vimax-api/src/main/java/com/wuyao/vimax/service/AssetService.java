package com.wuyao.vimax.service;

import com.wuyao.vimax.dto.GetUploadUrlRequest;
import com.wuyao.vimax.dto.UploadUrlResponse;
import com.wuyao.vimax.entity.Asset;
import com.wuyao.vimax.repository.AssetRepository;
import com.wuyao.vimax.service.MinioStorageService.ObjectStat;
import com.wuyao.vimax.service.MinioStorageService.UploadUrlResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 资产服务
 *
 * 负责文件上传、Asset记录管理
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssetService {

    private final AssetRepository assetRepository;
    private final MinioStorageService minioStorageService;

    /**
     * 获取上传URL
     */
    public UploadUrlResponse getUploadUrl(GetUploadUrlRequest request, Long userId) {
        log.info("生成上传URL: fileName={}", request.getFileName());

        // 生成MinIO预签名URL
        UploadUrlResult result = minioStorageService.generateUploadUrl(
            request.getFileName(),
            request.getFileType() != null ? request.getFileType() : "application/octet-stream"
        );

        // 返回响应
        UploadUrlResponse response = new UploadUrlResponse();
        response.setUploadUrl(result.uploadUrl());
        response.setAssetCode(result.objectKey());
        response.setExpiresIn(900L); // 15分钟

        return response;
    }

    /**
     * 确认上传完成，创建Asset记录
     */
    @Transactional
    public Asset confirmUpload(String objectKey, String assetType, String assetCategory,
                              String fileName, Long uploadedBy) {
        log.info("确认上传完成: objectKey={}, type={}", objectKey, assetType);

        // 从MinIO获取对象元数据
        ObjectStat stat = minioStorageService.getObjectStat(objectKey);

        // 创建Asset记录
        Asset asset = new Asset();
        asset.setAssetType(assetType);
        asset.setAssetCategory(assetCategory);
        asset.setS3Bucket(minioStorageService.getMinioBucket());
        asset.setS3Key(objectKey);
        asset.setFileName(fileName);
        asset.setMimeType(stat.contentType());
        asset.setFileSizeBytes(stat.size());
        asset.setUploadedBy(uploadedBy);
        asset.setSource("USER_UPLOAD");

        Asset saved = assetRepository.save(asset);
        log.info("Asset记录已创建: assetId={}, size={}", saved.getId(), stat.size());

        return saved;
    }

    /**
     * 创建资产（从Provider生成）
     */
    @Transactional
    public Asset createAsset(String assetCode, String name, String fileType,
                            String mimeType, Long fileSizeBytes, Long merchantId, Long userId) {
        log.info("创建资产: code={}, name={}, type={}", assetCode, name, fileType);

        Asset asset = new Asset();
        asset.setAssetType(fileType);
        asset.setS3Bucket(minioStorageService.getMinioBucket());
        asset.setS3Key(assetCode);
        asset.setFileName(name);
        asset.setMimeType(mimeType);
        asset.setFileSizeBytes(fileSizeBytes);
        asset.setUploadedBy(userId);
        asset.setSource("PROVIDER_GENERATED");

        Asset saved = assetRepository.save(asset);
        log.info("资产已创建: assetId={}", saved.getId());

        return saved;
    }

    /**
     * 查询商家资产列表
     */
    public List<Asset> getAssetsByMerchant(Long merchantId, String assetType) {
        // TODO: 新的 Asset 表没有 merchantId，需要通过关联表查询
        log.warn("getAssetsByMerchant 需要重构：Asset表已移除merchantId字段");
        return List.of();
    }

    /**
     * 查询资产
     */
    public Asset getAsset(Long assetId) {
        return assetRepository.findById(assetId)
                .orElseThrow(() -> new IllegalArgumentException("资产不存在: " + assetId));
    }

    /**
     * 获取资产下载URL
     */
    public String getAssetDownloadUrl(Long assetId) {
        Asset asset = getAsset(assetId);
        return minioStorageService.generateDownloadUrl(asset.getS3Key());
    }

    /**
     * 删除资产
     */
    @Transactional
    public void deleteAsset(Long assetId) {
        log.info("删除资产: assetId={}", assetId);

        Asset asset = getAsset(assetId);

        // 删除MinIO对象
        minioStorageService.deleteObject(asset.getS3Key());

        // 删除数据库记录
        assetRepository.delete(asset);

        log.info("资产已删除: assetId={}", assetId);
    }
}

