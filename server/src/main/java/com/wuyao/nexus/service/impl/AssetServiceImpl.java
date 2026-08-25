package com.wuyao.nexus.service.impl;

import com.wuyao.nexus.dto.AssetRequest;
import com.wuyao.nexus.dto.AssetResponse;
import com.wuyao.nexus.entity.Asset;
import com.wuyao.nexus.exception.BusinessException;
import com.wuyao.nexus.repository.AssetRepository;
import com.wuyao.nexus.service.AssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AssetServiceImpl implements AssetService {

    private final AssetRepository assetRepository;

    @Override
    public Page<AssetResponse> list(Long tenantId, String type, String category, Pageable pageable) {
        Asset.AssetType assetType = type != null ? Asset.AssetType.valueOf(type) : null;
        return assetRepository.findByFilters(tenantId, assetType, category, pageable)
                .map(this::toResponse);
    }

    @Override
    public AssetResponse get(Long id, Long tenantId) {
        Asset asset = assetRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new BusinessException("素材不存在"));
        return toResponse(asset);
    }

    @Override
    @Transactional
    public AssetResponse create(Long tenantId, Long userId, AssetRequest request) {
        Asset asset = new Asset();
        asset.setTenantId(tenantId);
        asset.setMerchantId(request.getMerchantId());
        asset.setStoreId(request.getStoreId());
        asset.setCode("A" + System.currentTimeMillis());
        asset.setName(request.getName());
        asset.setType(Asset.AssetType.valueOf(request.getType()));
        asset.setCategory(request.getCategory());
        asset.setFileUrl(request.getFileUrl());
        asset.setFileSize(request.getFileSize());
        asset.setMimeType(request.getMimeType());
        asset.setWidth(request.getWidth());
        asset.setHeight(request.getHeight());
        asset.setDuration(request.getDuration());
        asset.setThumbnailUrl(request.getThumbnailUrl());
        asset.setSource(request.getSource());
        asset.setCopyrightInfo(request.getCopyrightInfo());
        asset.setLicenseFileUrl(request.getLicenseFileUrl());
        asset.setLicenseScope(request.getLicenseScope());
        asset.setLicenseValidFrom(request.getLicenseValidFrom());
        asset.setLicenseValidUntil(request.getLicenseValidUntil());
        asset.setCreatedBy(userId);
        asset.setStatus(Asset.AssetStatus.AVAILABLE);

        asset = assetRepository.save(asset);
        return toResponse(asset);
    }

    @Override
    @Transactional
    public AssetResponse update(Long id, Long tenantId, AssetRequest request) {
        Asset asset = assetRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new BusinessException("素材不存在"));

        asset.setName(request.getName());
        asset.setCategory(request.getCategory());
        asset.setSource(request.getSource());
        asset.setCopyrightInfo(request.getCopyrightInfo());
        asset.setLicenseFileUrl(request.getLicenseFileUrl());
        asset.setLicenseScope(request.getLicenseScope());
        asset.setLicenseValidFrom(request.getLicenseValidFrom());
        asset.setLicenseValidUntil(request.getLicenseValidUntil());

        asset = assetRepository.save(asset);
        return toResponse(asset);
    }

    @Override
    @Transactional
    public void delete(Long id, Long tenantId) {
        Asset asset = assetRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new BusinessException("素材不存在"));

        asset.setDeletedAt(LocalDateTime.now());
        asset.setStatus(Asset.AssetStatus.DELETED);
        assetRepository.save(asset);
    }

    @Override
    public String requestUploadUrl(Long tenantId, String fileName, String mimeType) {
        // TODO: 实现预签名URL生成（MinIO/OSS/COS）
        // 这里返回占位符，实际应该调用对象存储服务
        return "https://mock-storage.example.com/upload/" + System.currentTimeMillis() + "/" + fileName;
    }

    @Override
    @Transactional
    public void confirmUpload(Long id, Long tenantId, String fileUrl) {
        Asset asset = assetRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new BusinessException("素材不存在"));

        asset.setFileUrl(fileUrl);
        asset.setStatus(Asset.AssetStatus.AVAILABLE);
        assetRepository.save(asset);
    }

    private AssetResponse toResponse(Asset asset) {
        AssetResponse response = new AssetResponse();
        BeanUtils.copyProperties(asset, response);
        response.setType(asset.getType().name());
        response.setStatus(asset.getStatus().name());
        return response;
    }
}
