package com.wuyao.nexus.controller;

import com.wuyao.nexus.dto.ApiResponse;
import com.wuyao.nexus.dto.MerchantRequest;
import com.wuyao.nexus.dto.MerchantResponse;
import com.wuyao.nexus.service.MerchantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/merchants")
@RequiredArgsConstructor
public class MerchantController {

    private final MerchantService merchantService;

    @GetMapping
    public ApiResponse<Page<MerchantResponse>> list(
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("tenantId") Long tenantId,
            Pageable pageable) {
        Page<MerchantResponse> merchants = merchantService.list(tenantId, pageable);
        return ApiResponse.success(merchants);
    }

    @GetMapping("/{id}")
    public ApiResponse<MerchantResponse> get(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId) {
        MerchantResponse merchant = merchantService.get(id, tenantId);
        return ApiResponse.success(merchant);
    }

    @PostMapping
    public ApiResponse<MerchantResponse> create(
            @RequestAttribute("tenantId") Long tenantId,
            @Valid @RequestBody MerchantRequest request) {
        MerchantResponse merchant = merchantService.create(tenantId, request);
        return ApiResponse.success(merchant, "商家创建成功");
    }

    @PutMapping("/{id}")
    public ApiResponse<MerchantResponse> update(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId,
            @Valid @RequestBody MerchantRequest request) {
        MerchantResponse merchant = merchantService.update(id, tenantId, request);
        return ApiResponse.success(merchant, "商家更新成功");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId) {
        merchantService.delete(id, tenantId);
        return ApiResponse.success(null, "商家删除成功");
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Void> toggleStatus(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId,
            @RequestBody Map<String, Boolean> body) {
        Boolean enabled = body.get("enabled");
        merchantService.toggleStatus(id, tenantId, enabled);
        return ApiResponse.success(null, "商家状态更新成功");
    }
}
