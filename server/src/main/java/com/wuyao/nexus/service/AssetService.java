package com.wuyao.nexus.service;

import com.wuyao.nexus.dto.AssetRequest;
import com.wuyao.nexus.dto.AssetResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AssetService {
    Page<AssetResponse> list(Long tenantId, String type, String category, Pageable pageable);
    AssetResponse get(Long id, Long tenantId);
    AssetResponse create(Long tenantId, Long userId, AssetRequest request);
    AssetResponse update(Long id, Long tenantId, AssetRequest request);
    void delete(Long id, Long tenantId);
    String requestUploadUrl(Long tenantId, String fileName, String mimeType);
    void confirmUpload(Long id, Long tenantId, String fileUrl);
}
