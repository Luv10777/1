package com.wuyao.growth.creative.inspiration;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import com.wuyao.growth.common.web.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/image/inspirations")
@RequiredArgsConstructor
public class ImageInspirationController {
    private final ImageInspirationService service;
    @GetMapping("/config") public ApiResponse<ImageInspirationDtos.Config> config() { return ApiResponse.ok(service.config()); }
    @PostMapping
    public ApiResponse<ImageInspirationDtos.View> create(@Valid @RequestBody ImageInspirationDtos.Create request,
                                                        @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.create(request, me.userId()));
    }
    @GetMapping
    public ApiResponse<PageResult<ImageInspirationDtos.History>> history(@RequestParam(defaultValue = "0") int page,
                                                                        @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(service.history(page, size));
    }
    @GetMapping("/{id}") public ApiResponse<ImageInspirationDtos.View> get(@PathVariable Long id) { return ApiResponse.ok(service.get(id)); }
}
