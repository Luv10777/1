package com.wuyao.nexus.controller;

import com.wuyao.nexus.dto.ApiResponse;
import com.wuyao.nexus.dto.BrandRequest;
import com.wuyao.nexus.dto.BrandResponse;
import com.wuyao.nexus.service.BrandService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class BrandController {

    private final BrandService brandService;

    @GetMapping("/merchants/{merchantId}/brands")
    public ApiResponse<Page<BrandResponse>> list(
            @PathVariable Long merchantId,
            @RequestAttribute("tenantId") Long tenantId,
            Pageable pageable) {
        Page<BrandResponse> brands = brandService.list(merchantId, tenantId, pageable);
        return ApiResponse.success(brands);
    }

    @GetMapping("/brands/{id}")
    public ApiResponse<BrandResponse> get(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId) {
        BrandResponse brand = brandService.get(id, tenantId);
        return ApiResponse.success(brand);
    }

    @PostMapping("/merchants/{merchantId}/brands")
    public ApiResponse<BrandResponse> create(
            @PathVariable Long merchantId,
            @RequestAttribute("tenantId") Long tenantId,
            @Valid @RequestBody BrandRequest request) {
        BrandResponse brand = brandService.create(merchantId, tenantId, request);
        return ApiResponse.success(brand, "品牌创建成功");
    }

    @PutMapping("/brands/{id}")
    public ApiResponse<BrandResponse> update(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId,
            @Valid @RequestBody BrandRequest request) {
        BrandResponse brand = brandService.update(id, tenantId, request);
        return ApiResponse.success(brand, "品牌更新成功");
    }

    @DeleteMapping("/brands/{id}")
    public ApiResponse<Void> delete(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId) {
        brandService.delete(id, tenantId);
        return ApiResponse.success(null, "品牌删除成功");
    }
}
