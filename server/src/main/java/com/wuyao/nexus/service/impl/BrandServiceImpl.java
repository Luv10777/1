package com.wuyao.nexus.service.impl;

import com.wuyao.nexus.dto.BrandRequest;
import com.wuyao.nexus.dto.BrandResponse;
import com.wuyao.nexus.entity.Brand;
import com.wuyao.nexus.exception.BusinessException;
import com.wuyao.nexus.repository.BrandRepository;
import com.wuyao.nexus.service.BrandService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class BrandServiceImpl implements BrandService {

    private final BrandRepository brandRepository;

    @Override
    public Page<BrandResponse> list(Long merchantId, Long tenantId, Pageable pageable) {
        return brandRepository.findByMerchantIdAndDeletedAtIsNull(merchantId, pageable)
                .map(this::toResponse);
    }

    @Override
    public BrandResponse get(Long id, Long tenantId) {
        Brand brand = brandRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new BusinessException("品牌不存在"));
        return toResponse(brand);
    }

    @Override
    @Transactional
    public BrandResponse create(Long merchantId, Long tenantId, BrandRequest request) {
        Brand brand = new Brand();
        brand.setTenantId(tenantId);
        brand.setMerchantId(merchantId);
        brand.setCode("B" + System.currentTimeMillis());
        brand.setName(request.getName());
        brand.setPositioning(request.getPositioning());
        brand.setTargetAudience(request.getTargetAudience());
        brand.setLanguageStyle(request.getLanguageStyle());
        brand.setPrimaryColor(request.getPrimaryColor());
        brand.setLogoAssets(request.getLogoAssets());
        brand.setPlatformStyles(request.getPlatformStyles());
        brand.setStatus(Brand.BrandStatus.ACTIVE);

        brand = brandRepository.save(brand);
        return toResponse(brand);
    }

    @Override
    @Transactional
    public BrandResponse update(Long id, Long tenantId, BrandRequest request) {
        Brand brand = brandRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new BusinessException("品牌不存在"));

        brand.setName(request.getName());
        brand.setPositioning(request.getPositioning());
        brand.setTargetAudience(request.getTargetAudience());
        brand.setLanguageStyle(request.getLanguageStyle());
        brand.setPrimaryColor(request.getPrimaryColor());
        brand.setLogoAssets(request.getLogoAssets());
        brand.setPlatformStyles(request.getPlatformStyles());
        brand.setVersion(brand.getVersion() + 1);

        brand = brandRepository.save(brand);
        return toResponse(brand);
    }

    @Override
    @Transactional
    public void delete(Long id, Long tenantId) {
        Brand brand = brandRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new BusinessException("品牌不存在"));

        brand.setDeletedAt(LocalDateTime.now());
        brand.setStatus(Brand.BrandStatus.DELETED);
        brandRepository.save(brand);
    }

    private BrandResponse toResponse(Brand brand) {
        BrandResponse response = new BrandResponse();
        BeanUtils.copyProperties(brand, response);
        response.setStatus(brand.getStatus().name());
        return response;
    }
}
