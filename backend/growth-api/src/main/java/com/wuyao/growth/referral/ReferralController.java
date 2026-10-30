package com.wuyao.growth.referral;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/referral")
@RequiredArgsConstructor
public class ReferralController {
    private final ReferralService service;

    @GetMapping("/me")
    public ApiResponse<ReferralDtos.Overview> me(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.overview(me.userId()));
    }

    @PostMapping("/code")
    public ApiResponse<ReferralDtos.Overview> code(@Valid @RequestBody ReferralDtos.CreateCode request, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.createCode(me.userId()));
    }
}
