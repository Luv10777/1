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
    private final ReferralWithdrawalService withdrawals;

    @GetMapping("/me")
    public ApiResponse<ReferralDtos.Overview> me(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.overview(me.userId()));
    }

    @PostMapping("/code")
    public ApiResponse<ReferralDtos.Overview> code(@Valid @RequestBody ReferralDtos.CreateCode request, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.createCode(me.userId()));
    }

    /** 把此刻全部可提现返利（扣除待扣回欠款）申请提现到已绑定的本人银行卡。 */
    @PostMapping("/withdrawals")
    public ApiResponse<ReferralDtos.Withdrawal> withdraw(@Valid @RequestBody ReferralDtos.ApplyWithdrawal request,
                                                         @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(withdrawals.apply(me.userId()));
    }
}
