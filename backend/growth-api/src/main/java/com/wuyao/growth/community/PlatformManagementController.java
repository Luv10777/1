package com.wuyao.growth.community;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import com.wuyao.growth.common.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/platform")
@RequiredArgsConstructor
public class PlatformManagementController {
    private final PlatformManagementService service;

    @ModelAttribute
    void preventCaching(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

    @GetMapping("/access")
    public ApiResponse<Map<String, Boolean>> access(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(Map.of("operator", service.isOperator(me)));
    }

    @GetMapping("/summary")
    public ApiResponse<PlatformDtos.Summary> summary(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.summary(me));
    }

    @GetMapping("/information")
    public ApiResponse<PageResult<PlatformDtos.InformationView>> information(@AuthenticationPrincipal AuthPrincipal me,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status, @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.information(me, page, size, status, category, keyword));
    }

    @GetMapping("/merchants")
    public ApiResponse<PageResult<PlatformDtos.MerchantView>> merchants(@AuthenticationPrincipal AuthPrincipal me,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status, @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.merchants(me, page, size, status, keyword));
    }

    @GetMapping("/identities")
    public ApiResponse<PageResult<RealNameDtos.AdminView>> identities(@AuthenticationPrincipal AuthPrincipal me,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status, @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.identities(me, page, size, status, keyword));
    }

    @PostMapping("/tenants/{tenantId}/information/{id}/status")
    public ApiResponse<Void> informationStatus(@AuthenticationPrincipal AuthPrincipal me, @PathVariable Long tenantId,
            @PathVariable Long id, @Valid @RequestBody PlatformDtos.ReviewRequest input) {
        service.reviewInformation(me, tenantId, id, input); return ApiResponse.ok();
    }

    @PostMapping("/tenants/{tenantId}/merchants/{id}/review")
    public ApiResponse<Void> merchantReview(@AuthenticationPrincipal AuthPrincipal me, @PathVariable Long tenantId,
            @PathVariable Long id, @Valid @RequestBody PlatformDtos.MerchantReviewRequest input) {
        service.reviewMerchant(me, tenantId, id, input); return ApiResponse.ok();
    }

    public record RevealRequest(@NotBlank @Size(min = 5, max = 500) String note) {}

    @PostMapping("/tenants/{tenantId}/merchants/{id}/reveal")
    public ApiResponse<PlatformDtos.MerchantDetailView> revealMerchant(@AuthenticationPrincipal AuthPrincipal me, @PathVariable Long tenantId,
            @PathVariable Long id, @Valid @RequestBody RevealRequest input) {
        return ApiResponse.ok(service.revealMerchant(me, tenantId, id, input.note()));
    }

    @PostMapping("/tenants/{tenantId}/identities/{id}/reveal")
    public ApiResponse<RealNameDtos.DetailView> reveal(@AuthenticationPrincipal AuthPrincipal me, @PathVariable Long tenantId,
            @PathVariable Long id, @Valid @RequestBody RevealRequest input) {
        return ApiResponse.ok(service.revealIdentity(me, tenantId, id, input.note()));
    }

    @PostMapping("/tenants/{tenantId}/identities/{id}/review")
    public ApiResponse<Void> identityReview(@AuthenticationPrincipal AuthPrincipal me, @PathVariable Long tenantId,
            @PathVariable Long id, @Valid @RequestBody RealNameDtos.ReviewRequest input) {
        service.reviewIdentity(me, tenantId, id, input); return ApiResponse.ok();
    }

    @GetMapping("/tenants/{tenantId}/audit/{type}/{id}")
    public ApiResponse<List<PlatformManagementAudit>> audit(@AuthenticationPrincipal AuthPrincipal me, @PathVariable Long tenantId,
            @PathVariable String type, @PathVariable Long id) {
        return ApiResponse.ok(service.auditTrail(me, tenantId, type, id));
    }
}
