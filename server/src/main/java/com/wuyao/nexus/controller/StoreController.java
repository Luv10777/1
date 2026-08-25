package com.wuyao.nexus.controller;

import com.wuyao.nexus.dto.ApiResponse;
import com.wuyao.nexus.dto.StoreRequest;
import com.wuyao.nexus.dto.StoreResponse;
import com.wuyao.nexus.service.StoreService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;

    @GetMapping("/merchants/{merchantId}/stores")
    public ApiResponse<Page<StoreResponse>> list(
            @PathVariable Long merchantId,
            @RequestAttribute("tenantId") Long tenantId,
            Pageable pageable) {
        Page<StoreResponse> stores = storeService.list(merchantId, tenantId, pageable);
        return ApiResponse.success(stores);
    }

    @GetMapping("/stores/{id}")
    public ApiResponse<StoreResponse> get(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId) {
        StoreResponse store = storeService.get(id, tenantId);
        return ApiResponse.success(store);
    }

    @PostMapping("/merchants/{merchantId}/stores")
    public ApiResponse<StoreResponse> create(
            @PathVariable Long merchantId,
            @RequestAttribute("tenantId") Long tenantId,
            @Valid @RequestBody StoreRequest request) {
        StoreResponse store = storeService.create(merchantId, tenantId, request);
        return ApiResponse.success(store, "门店创建成功");
    }

    @PutMapping("/stores/{id}")
    public ApiResponse<StoreResponse> update(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId,
            @Valid @RequestBody StoreRequest request) {
        StoreResponse store = storeService.update(id, tenantId, request);
        return ApiResponse.success(store, "门店更新成功");
    }

    @DeleteMapping("/stores/{id}")
    public ApiResponse<Void> delete(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId) {
        storeService.delete(id, tenantId);
        return ApiResponse.success(null, "门店删除成功");
    }

    @PutMapping("/stores/{id}/status")
    public ApiResponse<Void> toggleStatus(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId,
            @RequestBody Map<String, Boolean> body) {
        Boolean enabled = body.get("enabled");
        storeService.toggleStatus(id, tenantId, enabled);
        return ApiResponse.success(null, "门店状态更新成功");
    }
}
