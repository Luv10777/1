package com.wuyao.growth.referral;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import com.wuyao.growth.common.web.PageResult;
import com.wuyao.growth.community.BankCardDtos;
import com.wuyao.growth.community.PlatformManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 平台运营调整返利比例，查看邀请关系、返利明细和推广人排行，作废返利，审核提现并登记打款。 */
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

    @PostMapping("/commissions/{tenantId}/{id}/void")
    public ApiResponse<Void> voidCommission(@PathVariable Long tenantId, @PathVariable Long id,
                                            @Valid @RequestBody ReferralDtos.VoidCommission request,
                                            @AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        platform.voidCommission(me.userId(), tenantId, id, request.note());
        return ApiResponse.ok(null);
    }

    @GetMapping("/withdrawals")
    public ApiResponse<ReferralDtos.AdminWithdrawalPage> withdrawals(@RequestParam(required = false) String status,
                                                                     @RequestParam(defaultValue = "0") int page,
                                                                     @RequestParam(defaultValue = "20") int size,
                                                                     @AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        return ApiResponse.ok(platform.withdrawals(status, page, size));
    }

    @PostMapping("/withdrawals/{tenantId}/{id}/reveal")
    public ResponseEntity<ApiResponse<BankCardDtos.PayeeDetail>> reveal(@PathVariable Long tenantId, @PathVariable Long id,
                                                                        @Valid @RequestBody ReferralDtos.RevealPayee request,
                                                                        @AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(platform.revealPayee(me.userId(), tenantId, id, request.purpose())));
    }

    @PostMapping("/withdrawals/{tenantId}/{id}/reject")
    public ApiResponse<ReferralDtos.Withdrawal> reject(@PathVariable Long tenantId, @PathVariable Long id,
                                                       @Valid @RequestBody ReferralDtos.RejectWithdrawal request,
                                                       @AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        return ApiResponse.ok(platform.reject(me.userId(), tenantId, id, request.note()));
    }

    @PostMapping("/withdrawals/{tenantId}/{id}/paid")
    public ApiResponse<ReferralDtos.Withdrawal> paid(@PathVariable Long tenantId, @PathVariable Long id,
                                                     @Valid @RequestBody ReferralDtos.PayWithdrawal request,
                                                     @AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        return ApiResponse.ok(platform.markPaid(me.userId(), tenantId, id, request));
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
