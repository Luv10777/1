package com.wuyao.vimax.service;

import com.wuyao.vimax.dto.GetUploadUrlRequest;
import com.wuyao.vimax.dto.UploadUrlResponse;
import com.wuyao.vimax.entity.Asset;
import com.wuyao.vimax.repository.AssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 资产服务
 *
 * TODO: 需要重构以适配新的 Asset 实体结构
 * 新结构使用 s3_bucket/s3_key 而不是 code/name/fileUrl
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssetService {

    private final AssetRepository assetRepository;

    /**
     * 获取上传URL（临时实现）
     */
    public UploadUrlResponse getUploadUrl(GetUploadUrlRequest request, Long userId) {
        log.info("生成上传URL: fileName={}", request.getFileName());

        // TODO: 实现MinIO预签名URL生成
        UploadUrlResponse response = new UploadUrlResponse();
        response.setUploadUrl("http://localhost:9000/temp/" + request.getFileName());
        // response.setAssetId 字段可能不存在，移除此行

        return response;
    }

    /**
     * 创建资产
     */
    @Transactional
    public Asset createAsset(String assetCode, String name, String fileType,
                            String mimeType, Long fileSizeBytes, Long merchantId, Long userId) {
        log.info("创建资产: code={}, name={}, type={}", assetCode, name, fileType);

        Asset asset = new Asset();
        asset.setAssetType(fileType);
        asset.setS3Bucket("vimax-assets");
        asset.setS3Key(assetCode);
        asset.setFileName(name);
        asset.setMimeType(mimeType);
        asset.setFileSizeBytes(fileSizeBytes);
        asset.setUploadedBy(userId);

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
     * 删除资产（临时禁用）
     */
    @Transactional
    public void deleteAsset(Long assetId) {
        log.info("删除资产: assetId={}", assetId);
        // TODO: 实现删除逻辑
    }
}

