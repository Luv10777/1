package com.wuyao.growth.video.plan;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import com.wuyao.growth.common.web.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/video/plans")
@RequiredArgsConstructor
public class VideoPlanController {
    private final VideoPlanService service;
    @GetMapping("/config") public ApiResponse<VideoPlanDtos.Config> config() { return ApiResponse.ok(service.config()); }
    @PostMapping
    public ApiResponse<VideoPlanDtos.View> create(@Valid @RequestBody VideoPlanDtos.Create request,
                                                 @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.create(request, me.userId()));
    }
    @GetMapping
    public ApiResponse<PageResult<VideoPlanDtos.History>> history(@RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(service.history(page, size));
    }
    @GetMapping("/{id}") public ApiResponse<VideoPlanDtos.View> get(@PathVariable Long id) { return ApiResponse.ok(service.get(id)); }
}
