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
