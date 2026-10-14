package com.wuyao.growth.community;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import com.wuyao.growth.common.web.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class CommunityController {
    private final CommunityService service;

    @PostMapping("/api/merchant-alliance/applications")
    public ApiResponse<CommunityDtos.ApplicationView> apply(@Valid @RequestBody CommunityDtos.ApplicationRequest request,
                                                             @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.apply(request, me.userId()));
    }

    @GetMapping("/api/merchant-alliance/applications")
    public ApiResponse<List<CommunityDtos.ApplicationView>> applications() {
        return ApiResponse.ok(service.applications());
    }

    @PostMapping("/api/information-publications")
    public ApiResponse<CommunityDtos.InformationView> publish(@Valid @RequestBody CommunityDtos.InformationRequest request,
                                                               @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.publish(request, me.userId()));
    }

    @GetMapping("/api/information-publications")
    public ApiResponse<List<CommunityDtos.InformationView>> publications() {
        return ApiResponse.ok(service.publications());
    }

    @PutMapping("/api/information-publications/{id}")
    public ApiResponse<CommunityDtos.InformationView> update(@PathVariable Long id,
                                                              @Valid @RequestBody CommunityDtos.InformationRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/api/information-publications/{id}")
    public ApiResponse<Void> takeOffline(@PathVariable Long id) {
        service.takeOffline(id);
        return ApiResponse.ok();
    }

    @GetMapping("/api/public/information")
    public ApiResponse<PageResult<CommunityDtos.InformationView>> publicFeed(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.publicFeed(page, size, category, keyword));
    }

    @GetMapping("/api/public/information/{id}")
    public ApiResponse<CommunityDtos.InformationView> publicInformation(@PathVariable Long id) {
        return ApiResponse.ok(service.publicInformation(id));
    }
}
