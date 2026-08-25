package com.wuyao.nexus.service;

import com.wuyao.nexus.dto.MerchantRequest;
import com.wuyao.nexus.dto.MerchantResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface MerchantService {
    Page<MerchantResponse> list(Long tenantId, Pageable pageable);
    MerchantResponse get(Long id, Long tenantId);
    MerchantResponse create(Long tenantId, MerchantRequest request);
    MerchantResponse update(Long id, Long tenantId, MerchantRequest request);
    void delete(Long id, Long tenantId);
    void toggleStatus(Long id, Long tenantId, boolean enabled);
}
