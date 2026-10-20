package com.wuyao.growth.alliance;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import com.wuyao.growth.community.PlatformManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 平台运营的联盟券接口：供券审核、申诉处理、结算标记和汇总。只对平台运营开放。 */
@RestController
@RequestMapping("/api/platform")
@RequiredArgsConstructor
public class AlliancePlatformController {
    private final AlliancePlatformService platform;
    private final PlatformManagementService management;

    @GetMapping("/alliance/offers")
    public ApiResponse<List<AllianceDtos.PlatformOffer>> pendingOffers(@AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        return ApiResponse.ok(platform.pendingOffers());
    }

    @PostMapping("/tenants/{tenantId}/alliance/offers/{id}/review")
    public ApiResponse<AllianceDtos.OfferView> review(@PathVariable Long tenantId, @PathVariable Long id,
                                                      @Valid @RequestBody AllianceDtos.ReviewRequest request,
                                                      @AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        return ApiResponse.ok(platform.review(tenantId, id, request, me.userId()));
    }

    @GetMapping("/alliance/disputes")
    public ApiResponse<List<AllianceDtos.PlatformDispute>> disputes(@AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        return ApiResponse.ok(platform.disputes());
    }

    @PostMapping("/tenants/{tenantId}/alliance/vouchers/{ref}/dispute-resolution")
    public ApiResponse<Void> resolve(@PathVariable Long tenantId, @PathVariable UUID ref,
                                     @Valid @RequestBody AllianceDtos.DisputeResolution request,
                                     @AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        platform.resolveDispute(tenantId, ref, request);
        return ApiResponse.ok();
    }

    @GetMapping("/alliance/settlements")
    public ApiResponse<List<AllianceDtos.PlatformSettlement>> settlements(@RequestParam(required = false) String status,
                                                                         @AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        return ApiResponse.ok(platform.settlements(status));
    }

    @PostMapping("/tenants/{tenantId}/alliance/settlements/{id}/paid")
    public ApiResponse<AllianceDtos.SettlementView> paid(@PathVariable Long tenantId, @PathVariable Long id,
                                                         @Valid @RequestBody AllianceDtos.PaidRequest request,
                                                         @AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        return ApiResponse.ok(platform.markPaid(tenantId, id, request.reference(), me.userId()));
    }

    @GetMapping("/alliance/summary")
    public ApiResponse<AllianceDtos.Summary> summary(@AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        return ApiResponse.ok(platform.summary());
    }
}
