package com.wuyao.growth.billing;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Operator allowlist is an explicit server setting; a merchant OWNER never receives it implicitly. */
@RestController @RequestMapping("/api/billing/operations") @RequiredArgsConstructor
public class BillingOperationsController {
    private final BillingProperties config;
    private final BillingService service;
    private final BillingLedgerReport ledger;
    private final BillingRedeemCodeService redeemCodes;
    /** 全平台烟火流水：days 为 1（今天）至 90；type、capability 可选；keyword 按商户名或编号筛选。 */
    @GetMapping("/ledger") public ApiResponse<com.wuyao.growth.common.web.PageResult<BillingDtos.PlatformLedgerEntry>> ledger(
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="7") int days,
        @RequestParam(required=false) String type,@RequestParam(required=false) String capability,
        @RequestParam(required=false) String keyword,@AuthenticationPrincipal AuthPrincipal me) {
        requireOperator(me); return ApiResponse.ok(ledger.entries(page,size,days,type,capability,keyword));
    }
    @GetMapping("/ledger/summary") public ApiResponse<BillingDtos.PlatformLedgerSummary> ledgerSummary(
        @RequestParam(defaultValue="7") int days,@AuthenticationPrincipal AuthPrincipal me) {
        requireOperator(me); return ApiResponse.ok(ledger.summary(days));
    }
    /** 烟火按天合计：最近 days 天（含今天，1 至 180），后台工作台用它做时段对比。 */
    @GetMapping("/ledger/daily") public ApiResponse<java.util.List<BillingDtos.DailyCredits>> ledgerDaily(
        @RequestParam(defaultValue="30") int days,@AuthenticationPrincipal AuthPrincipal me) {
        requireOperator(me); return ApiResponse.ok(ledger.daily(days));
    }
    /** 烟火看板：全平台累计充值、赠送、消耗，以及每个商户的余额。 */
    @GetMapping("/overview") public ApiResponse<BillingDtos.CreditOverview> overview(@AuthenticationPrincipal AuthPrincipal me) {
        requireOperator(me); return ApiResponse.ok(ledger.overview());
    }
    @GetMapping("/redeem-codes") public ApiResponse<com.wuyao.growth.common.web.PageResult<BillingDtos.RedeemCodeView>> redeemCodes(
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,
        @RequestParam(required=false) String status,@RequestParam(required=false) String kind,
        @RequestParam(required=false) String keyword,@AuthenticationPrincipal AuthPrincipal me) {
        requireOperator(me); return ApiResponse.ok(redeemCodes.list(page,size,status,kind,keyword));
    }
    @PostMapping("/redeem-codes") public ApiResponse<java.util.List<BillingDtos.RedeemCodeView>> createRedeemCodes(
        @Valid @RequestBody BillingDtos.NewRedeemCodes input,@AuthenticationPrincipal AuthPrincipal me) {
        requireOperator(me); return ApiResponse.ok(redeemCodes.create(input,me.userId()));
    }
    @PostMapping("/redeem-codes/{id}/void") public ApiResponse<BillingDtos.RedeemCodeView> voidRedeemCode(
        @PathVariable Long id,@AuthenticationPrincipal AuthPrincipal me) {
        requireOperator(me); return ApiResponse.ok(redeemCodes.voidCode(id,me.userId()));
    }
    @GetMapping("/plans") public ApiResponse<java.util.List<BillingDtos.PlanListing>> plans(@AuthenticationPrincipal AuthPrincipal me) {
        requireOperator(me); return ApiResponse.ok(service.operatorPlans());
    }
    @PostMapping("/plans") public ApiResponse<BillingDtos.PlanSnapshot> create(@Valid @RequestBody BillingDtos.NewPlan input,
        @AuthenticationPrincipal AuthPrincipal me) { requireOperator(me); return ApiResponse.ok(service.createPlan(input)); }
    @PostMapping("/plans/{id}/publication") public ApiResponse<Void> publish(@PathVariable Long id,
        @RequestBody BillingDtos.Publication input,@AuthenticationPrincipal AuthPrincipal me) {
        requireOperator(me); service.publish(id,input.published(),input.replacePublished()); return ApiResponse.ok(null);
    }
    @GetMapping("/tenants/{tenantId}/orders/{id}") public ApiResponse<BillingDtos.OrderView> order(
        @PathVariable Long tenantId,@PathVariable String id,@AuthenticationPrincipal AuthPrincipal me) {
        requireOperator(me); return ApiResponse.ok(TenantContext.runAs(tenantId,()->service.order(id)));
    }
    @PostMapping("/tenants/{tenantId}/orders/{id}/confirm") public ApiResponse<BillingDtos.OrderView> confirm(
        @PathVariable Long tenantId,@PathVariable String id,@Valid @RequestBody BillingDtos.Confirmation input,
        @AuthenticationPrincipal AuthPrincipal me) {
        requireOperator(me); return ApiResponse.ok(TenantContext.runAs(tenantId,()->service.confirmPayment(id,input,me.userId())));
    }
    private void requireOperator(AuthPrincipal me) {
        if(!config.getOperatorUserIds().contains(me.userId())) throw BizException.of(ErrorCode.FORBIDDEN,"没有平台运营权限");
    }
}
