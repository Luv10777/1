package com.wuyao.nexus.service;

import com.wuyao.nexus.dto.BrandRequest;
import com.wuyao.nexus.dto.BrandResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface BrandService {
    Page<BrandResponse> list(Long merchantId, Long tenantId, Pageable pageable);
    BrandResponse get(Long id, Long tenantId);
    BrandResponse create(Long merchantId, Long tenantId, BrandRequest request);
    BrandResponse update(Long id, Long tenantId, BrandRequest request);
    void delete(Long id, Long tenantId);
}
