package com.wuyao.nexus.service;

import com.wuyao.nexus.dto.StoreRequest;
import com.wuyao.nexus.dto.StoreResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface StoreService {
    Page<StoreResponse> list(Long merchantId, Long tenantId, Pageable pageable);
    StoreResponse get(Long id, Long tenantId);
    StoreResponse create(Long merchantId, Long tenantId, StoreRequest request);
    StoreResponse update(Long id, Long tenantId, StoreRequest request);
    void delete(Long id, Long tenantId);
    void toggleStatus(Long id, Long tenantId, boolean enabled);
}
