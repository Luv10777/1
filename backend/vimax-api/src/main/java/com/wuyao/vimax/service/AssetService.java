package com.wuyao.vimax.service;

import com.wuyao.vimax.config.MinioConfig;
import com.wuyao.vimax.dto.GetUploadUrlRequest;
import com.wuyao.vimax.dto.UploadUrlResponse;
import com.wuyao.vimax.entity.Asset;
import com.wuyao.vimax.repository.AssetRepository;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 资产服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssetService {

    private final AssetRepository assetRepository;
    private final MinioClient minioClient;
    private final MinioConfig minioConfig;

    /**
     * 获取预签名上传 URL
     */
    public UploadUrlResponse getUploadUrl(GetUploadUrlRequest request, Long userId) {
        log.info("生成上传 URL：fileName={}, fileType={}", request.getFileName(), request.getFileType());

        // 1. 生成唯一资产代码
        String assetCode = UUID.randomUUID().toString().replace("-", "");

        // 2. 根据文件类型选择存储桶
        String bucket = selectBucket(request.getFileType());

        // 3. 生成对象键
        String objectKey = generateObjectKey(assetCode, request.getFileName());

        // 4. 生成预签名 PUT URL（15 分钟有效）
        try {
            String uploadUrl = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket(bucket)
                            .object(objectKey)
                            .expiry(15, TimeUnit.MINUTES)
                            .build()
            );

            UploadUrlResponse response = new UploadUrlResponse();
            response.setUploadUrl(uploadUrl);
            response.setAssetCode(assetCode);
            response.setExpiresIn(900L);  // 15 分钟 = 900 秒

            log.info("上传 URL 生成成功：assetCode={}, bucket={}, key={}", assetCode, bucket, objectKey);
            return response;

        } catch (Exception e) {
            log.error("生成预签名 URL 失败", e);
            throw new RuntimeException("生成上传 URL 失败: " + e.getMessage());
        }
    }

    /**
     * 创建资产记录
     */
    @Transactional
    public Asset createAsset(String assetCode, String name, String type, String mimeType,
                             Long fileSize, Long merchantId, Long userId) {
        log.info("创建资产记录：assetCode={}, name={}, type={}", assetCode, name, type);

        // 检查资产代码是否已存在
        if (assetRepository.existsByCode(assetCode)) {
            throw new IllegalArgumentException("资产代码已存在: " + assetCode);
        }

        // 根据类型选择存储桶
        String bucket = selectBucket(type);
        String objectKey = generateObjectKey(assetCode, name);

        Asset asset = new Asset();
        asset.setCode(assetCode);
        asset.setName(name);
        asset.setType(type);
        asset.setMimeType(mimeType);
        asset.setFileSize(fileSize);
        asset.setMerchantId(merchantId);
        asset.setCreatedBy(userId);
        asset.setSource("UPLOAD");

        // 构建文件 URL
        String fileUrl = String.format("%s/%s/%s", minioConfig.getEndpoint(), bucket, objectKey);
        asset.setFileUrl(fileUrl);

        Asset saved = assetRepository.save(asset);
        log.info("资产记录创建成功：id={}, code={}", saved.getId(), saved.getCode());

        return saved;
    }

    /**
     * 查询商家的资产列表
     */
    public List<Asset> getAssetsByMerchant(Long merchantId, String type) {
        if (type != null && !type.isEmpty()) {
            return assetRepository.findByMerchantIdAndTypeAndStatus(merchantId, type, "AVAILABLE");
        }
        return assetRepository.findByMerchantIdAndStatus(merchantId, "AVAILABLE");
    }

    /**
     * 删除资产（软删除）
     */
    @Transactional
    public void deleteAsset(Long assetId) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new IllegalArgumentException("资产不存在: " + assetId));

        asset.setStatus("DELETED");
        asset.setDeletedAt(LocalDateTime.now());
        assetRepository.save(asset);

        log.info("资产已删除：assetId={}", assetId);
    }

    /**
     * 根据文件类型选择存储桶
     */
    private String selectBucket(String fileType) {
        return switch (fileType.toUpperCase()) {
            case "VIDEO" -> minioConfig.getBucketVideos();
            case "IMAGE", "AUDIO", "DOCUMENT" -> minioConfig.getBucketAssets();
            default -> minioConfig.getBucketTemp();
        };
    }

    /**
     * 生成对象键
     */
    private String generateObjectKey(String assetCode, String fileName) {
        String extension = "";
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0) {
            extension = fileName.substring(lastDot);
        }
        return String.format("uploads/%s/%s%s",
                LocalDateTime.now().toLocalDate().toString(),
                assetCode,
                extension);
    }
}
