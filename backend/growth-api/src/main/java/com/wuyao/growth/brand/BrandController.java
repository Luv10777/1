package com.wuyao.growth.brand;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/brands")
@RequiredArgsConstructor
public class BrandController {

    private final BrandService brandService;

    @GetMapping
    public ApiResponse<List<BrandDtos.View>> list() {
        return ApiResponse.ok(brandService.list());
    }

    @PostMapping
    public ApiResponse<BrandDtos.View> create(@Valid @RequestBody BrandDtos.CreateRequest req,
                                              @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(brandService.create(req, me.userId()));
    }

    @GetMapping("/{id}")
    public ApiResponse<BrandDtos.View> get(@PathVariable Long id) {
        return ApiResponse.ok(brandService.get(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<BrandDtos.View> update(@PathVariable Long id,
                                              @Valid @RequestBody BrandDtos.UpdateRequest req) {
        return ApiResponse.ok(brandService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> archive(@PathVariable Long id) {
        brandService.archive(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/default")
    public ApiResponse<BrandDtos.View> makeDefault(@PathVariable Long id) {
        return ApiResponse.ok(brandService.makeDefault(id));
    }
}
