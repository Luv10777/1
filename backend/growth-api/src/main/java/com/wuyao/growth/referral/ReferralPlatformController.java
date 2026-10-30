package com.wuyao.growth.referral;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import com.wuyao.growth.community.PlatformManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 平台运营调整返利比例。 */
@RestController
@RequestMapping("/api/platform/referral")
@RequiredArgsConstructor
public class ReferralPlatformController {
    private final ReferralService service;
    private final PlatformManagementService management;

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
