package com.wuyao.growth.video.analysis;

import com.wuyao.growth.asset.AssetDtos;
import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import com.wuyao.growth.common.web.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/video/analyses")
@RequiredArgsConstructor
public class VideoAnalysisController {
    private final VideoAnalysisService service;
    @GetMapping("/limits") public ApiResponse<VideoAnalysisDtos.Limits> limits() { return ApiResponse.ok(service.limits()); }
    @PostMapping("/upload-url")
    public ApiResponse<AssetDtos.UploadTicket> upload(@Valid @RequestBody VideoAnalysisDtos.Upload request,
                                                     @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.upload(request, me.userId()));
    }
    @PostMapping
    public ApiResponse<VideoAnalysisDtos.View> create(@Valid @RequestBody VideoAnalysisDtos.Create request,
                                                     @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.create(request, me.userId()));
    }
    @PostMapping("/import-url")
    public ApiResponse<VideoAnalysisDtos.View> importUrl(@Valid @RequestBody VideoAnalysisDtos.Import request,
                                                        @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.importUrl(request, me.userId()));
    }
    @GetMapping("/{id}") public ApiResponse<VideoAnalysisDtos.View> get(@PathVariable Long id) { return ApiResponse.ok(service.get(id)); }
    @GetMapping
    public ApiResponse<PageResult<VideoAnalysisDtos.View>> list(@RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(page, size));
    }
    @PutMapping("/{id}/name")
    public ApiResponse<VideoAnalysisDtos.View> rename(@PathVariable Long id, @Valid @RequestBody VideoAnalysisDtos.Rename request) {
        return ApiResponse.ok(service.rename(id, request));
    }
}
