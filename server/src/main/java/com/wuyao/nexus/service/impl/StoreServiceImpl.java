package com.wuyao.nexus.service.impl;

import com.wuyao.nexus.dto.StoreRequest;
import com.wuyao.nexus.dto.StoreResponse;
import com.wuyao.nexus.entity.Store;
import com.wuyao.nexus.exception.BusinessException;
import com.wuyao.nexus.repository.StoreRepository;
import com.wuyao.nexus.service.StoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class StoreServiceImpl implements StoreService {

    private final StoreRepository storeRepository;

    @Override
    public Page<StoreResponse> list(Long merchantId, Long tenantId, Pageable pageable) {
        return storeRepository.findByMerchantIdAndDeletedAtIsNull(merchantId, pageable)
                .map(this::toResponse);
    }

    @Override
    public StoreResponse get(Long id, Long tenantId) {
        Store store = storeRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new BusinessException("门店不存在"));
        return toResponse(store);
    }

    @Override
    @Transactional
    public StoreResponse create(Long merchantId, Long tenantId, StoreRequest request) {
        Store store = new Store();
        store.setTenantId(tenantId);
        store.setMerchantId(merchantId);
        store.setCode("S" + System.currentTimeMillis());
        store.setName(request.getName());
        store.setAddress(request.getAddress());
        store.setCity(request.getCity());
        store.setProvince(request.getProvince());
        store.setLatitude(request.getLatitude());
        store.setLongitude(request.getLongitude());
        store.setContactPhone(request.getContactPhone());
        store.setBusinessHours(request.getBusinessHours());
        store.setStatus(Store.StoreStatus.ACTIVE);

        store = storeRepository.save(store);
        return toResponse(store);
    }

    @Override
    @Transactional
    public StoreResponse update(Long id, Long tenantId, StoreRequest request) {
        Store store = storeRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new BusinessException("门店不存在"));

        store.setName(request.getName());
        store.setAddress(request.getAddress());
        store.setCity(request.getCity());
        store.setProvince(request.getProvince());
        store.setLatitude(request.getLatitude());
        store.setLongitude(request.getLongitude());
        store.setContactPhone(request.getContactPhone());
        store.setBusinessHours(request.getBusinessHours());

        store = storeRepository.save(store);
        return toResponse(store);
    }

    @Override
    @Transactional
    public void delete(Long id, Long tenantId) {
        Store store = storeRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new BusinessException("门店不存在"));

        store.setDeletedAt(LocalDateTime.now());
        store.setStatus(Store.StoreStatus.DELETED);
        storeRepository.save(store);
    }

    @Override
    @Transactional
    public void toggleStatus(Long id, Long tenantId, boolean enabled) {
        Store store = storeRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new BusinessException("门店不存在"));

        store.setStatus(enabled ? Store.StoreStatus.ACTIVE : Store.StoreStatus.SUSPENDED);
        storeRepository.save(store);
    }

    private StoreResponse toResponse(Store store) {
        StoreResponse response = new StoreResponse();
        BeanUtils.copyProperties(store, response);
        response.setStatus(store.getStatus().name());
        return response;
    }
}
