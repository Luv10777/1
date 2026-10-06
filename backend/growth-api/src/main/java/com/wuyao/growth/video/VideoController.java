package com.wuyao.growth.video;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import com.wuyao.growth.common.web.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/video")
@RequiredArgsConstructor
public class VideoController {
    private final VideoWorkflowService service;

    @GetMapping("/capabilities")
    public ApiResponse<List<VideoDtos.Capability>> capabilities() { return ApiResponse.ok(service.capabilities()); }

    @PostMapping("/workflows")
    public ApiResponse<VideoDtos.View> create(@Valid @RequestBody VideoDtos.Create request,
                                              @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.create(request, me.userId()));
    }

    @GetMapping("/workflows/{id}")
    public ApiResponse<VideoDtos.View> get(@PathVariable Long id) { return ApiResponse.ok(service.get(id)); }

    @PostMapping("/workflows/{id}/cancel")
    public ApiResponse<VideoDtos.View> cancel(@PathVariable Long id) { return ApiResponse.ok(service.cancel(id)); }

    @PostMapping("/workflows/{id}/retry")
    public ApiResponse<VideoDtos.View> retry(@PathVariable Long id, @Valid @RequestBody VideoDtos.Retry request,
                                             @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.retry(id, request.taskId(), me.userId()));
    }

    @GetMapping("/workflows")
    public ApiResponse<PageResult<VideoDtos.History>> history(@RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.history(page, size));
    }
}
