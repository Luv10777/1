package com.wuyao.growth.referral;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import com.wuyao.growth.common.web.PageResult;
import com.wuyao.growth.community.PlatformManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 平台运营调整返利比例，查看邀请关系、返利明细和推广人排行。 */
@RestController
@RequestMapping("/api/platform/referral")
@RequiredArgsConstructor
public class ReferralPlatformController {
    private final ReferralService service;
    private final ReferralPlatformService platform;
    private final PlatformManagementService management;

    @GetMapping("/relations")
    public ApiResponse<PageResult<ReferralDtos.AdminRelation>> relations(@RequestParam(required = false) String keyword,
                                                                         @RequestParam(defaultValue = "ALL") String scope,
                                                                         @RequestParam(defaultValue = "0") int page,
                                                                         @RequestParam(defaultValue = "20") int size,
                                                                         @AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        return ApiResponse.ok(platform.relations(keyword, scope, page, size));
    }

    @GetMapping("/commissions")
    public ApiResponse<ReferralDtos.AdminCommissionPage> commissions(@RequestParam(required = false) Long referrerUserId,
                                                                     @RequestParam(required = false) String state,
                                                                     @RequestParam(defaultValue = "0") int page,
                                                                     @RequestParam(defaultValue = "20") int size,
                                                                     @AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        return ApiResponse.ok(platform.commissions(referrerUserId, state, page, size));
    }

    @GetMapping("/promoters")
    public ApiResponse<List<ReferralDtos.AdminPromoter>> promoters(@AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        return ApiResponse.ok(platform.promoters());
    }

    @GetMapping("/rate")
    public ApiResponse<ReferralDtos.RateView> rate(@AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        return ApiResponse.ok(service.rate());
    }

    @PutMapping("/rate")
    public ApiResponse<ReferralDtos.RateView> updateRate(@Valid @RequestBody ReferralDtos.UpdateRate request,
                                                         @AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        return ApiResponse.ok(service.updateRate(me.userId(), request));
    }
}
