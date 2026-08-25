package com.wuyao.nexus.service.impl;

import com.wuyao.nexus.dto.MerchantRequest;
import com.wuyao.nexus.dto.MerchantResponse;
import com.wuyao.nexus.entity.Merchant;
import com.wuyao.nexus.exception.BusinessException;
import com.wuyao.nexus.repository.MerchantRepository;
import com.wuyao.nexus.service.MerchantService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class MerchantServiceImpl implements MerchantService {

    private final MerchantRepository merchantRepository;

    @Override
    public Page<MerchantResponse> list(Long tenantId, Pageable pageable) {
        return merchantRepository.findByTenantIdAndDeletedAtIsNull(tenantId, pageable)
                .map(this::toResponse);
    }

    @Override
    public MerchantResponse get(Long id, Long tenantId) {
        Merchant merchant = merchantRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new BusinessException("商家不存在"));
        return toResponse(merchant);
    }

    @Override
    @Transactional
    public MerchantResponse create(Long tenantId, MerchantRequest request) {
        Merchant merchant = new Merchant();
        merchant.setTenantId(tenantId);
        merchant.setCode("M" + System.currentTimeMillis());
        merchant.setName(request.getName());
        merchant.setIndustry(request.getIndustry());
        merchant.setLogoUrl(request.getLogoUrl());
        merchant.setContactName(request.getContactName());
        merchant.setContactPhone(request.getContactPhone());
        merchant.setContactEmail(request.getContactEmail());
        merchant.setCompleteness(calculateCompleteness(request));
        merchant.setStatus(Merchant.MerchantStatus.ACTIVE);

        merchant = merchantRepository.save(merchant);
        return toResponse(merchant);
    }

    @Override
    @Transactional
    public MerchantResponse update(Long id, Long tenantId, MerchantRequest request) {
        Merchant merchant = merchantRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new BusinessException("商家不存在"));

        merchant.setName(request.getName());
        merchant.setIndustry(request.getIndustry());
        merchant.setLogoUrl(request.getLogoUrl());
        merchant.setContactName(request.getContactName());
        merchant.setContactPhone(request.getContactPhone());
        merchant.setContactEmail(request.getContactEmail());
        merchant.setCompleteness(calculateCompleteness(request));

        merchant = merchantRepository.save(merchant);
        return toResponse(merchant);
    }

    @Override
    @Transactional
    public void delete(Long id, Long tenantId) {
        Merchant merchant = merchantRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new BusinessException("商家不存在"));

        merchant.setDeletedAt(LocalDateTime.now());
        merchant.setStatus(Merchant.MerchantStatus.DELETED);
        merchantRepository.save(merchant);
    }

    @Override
    @Transactional
    public void toggleStatus(Long id, Long tenantId, boolean enabled) {
        Merchant merchant = merchantRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new BusinessException("商家不存在"));

        merchant.setStatus(enabled ? Merchant.MerchantStatus.ACTIVE : Merchant.MerchantStatus.SUSPENDED);
        merchantRepository.save(merchant);
    }

    private MerchantResponse toResponse(Merchant merchant) {
        MerchantResponse response = new MerchantResponse();
        BeanUtils.copyProperties(merchant, response);
        response.setStatus(merchant.getStatus().name());
        return response;
    }

    private Integer calculateCompleteness(MerchantRequest request) {
        int score = 0;
        if (request.getName() != null) score += 20;
        if (request.getIndustry() != null) score += 20;
        if (request.getLogoUrl() != null) score += 20;
        if (request.getContactName() != null) score += 20;
        if (request.getContactPhone() != null) score += 20;
        return score;
    }
}
