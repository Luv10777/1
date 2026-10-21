package com.wuyao.growth.billing;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.*;
import com.wuyao.growth.iam.repository.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/billing") @RequiredArgsConstructor
public class BillingController {
    private final BillingService service;
    private final BillingProperties config;
    private final BillingCheckoutService checkout;
    private final UserRepository users;
    @GetMapping("/summary")
    public ApiResponse<BillingDtos.Summary> summary(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.summary(owner(me),config.getOperatorUserIds().contains(me.userId())));
    }
    @GetMapping("/plans") public ApiResponse<List<BillingDtos.PlanSnapshot>> plans() { return ApiResponse.ok(service.plans()); }
    @GetMapping("/ledger") public ApiResponse<BillingDtos.History<BillingDtos.LedgerView>> ledger(
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return ApiResponse.ok(service.ledger(page,size)); }
    @GetMapping("/orders") public ApiResponse<BillingDtos.History<BillingDtos.OrderView>> orders(
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@AuthenticationPrincipal AuthPrincipal me) {
        requireOwner(me); return ApiResponse.ok(service.orders(page,size));
    }
    @PostMapping("/orders/topup") public ApiResponse<BillingDtos.OrderView> topUp(@Valid @RequestBody BillingDtos.TopUp input,
        @AuthenticationPrincipal AuthPrincipal me) { requireOwner(me); return ApiResponse.ok(checkout.topUp(input)); }
    @PostMapping("/orders/subscription") public ApiResponse<BillingDtos.OrderView> subscribe(@Valid @RequestBody BillingDtos.Subscribe input,
        @AuthenticationPrincipal AuthPrincipal me) { requireOwner(me); return ApiResponse.ok(checkout.subscribe(input)); }
    @GetMapping("/topups") public ApiResponse<BillingDtos.History<BillingDtos.TopupRecord>> topups(
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@AuthenticationPrincipal AuthPrincipal me) {
        requireOwner(me); return ApiResponse.ok(service.topups(page,size));
    }
    @GetMapping("/orders/{id}") public ApiResponse<BillingDtos.OrderView> order(@PathVariable String id,
        @AuthenticationPrincipal AuthPrincipal me) { requireOwner(me); return ApiResponse.ok(service.order(id)); }
    @PostMapping("/orders/{id}/refresh") public ApiResponse<BillingDtos.OrderView> refresh(@PathVariable String id,
        @AuthenticationPrincipal AuthPrincipal me) { requireOwner(me); return ApiResponse.ok(checkout.refresh(id)); }
    @PostMapping("/orders/{id}/alipay") public ApiResponse<String> alipay(@PathVariable String id,
        @AuthenticationPrincipal AuthPrincipal me) { requireOwner(me); return ApiResponse.ok(checkout.alipayUrl(id)); }
    @PostMapping("/orders/{id}/cancel") public ApiResponse<BillingDtos.OrderView> cancel(@PathVariable String id,
        @AuthenticationPrincipal AuthPrincipal me) { requireOwner(me); return ApiResponse.ok(checkout.cancel(id)); }
    private boolean owner(AuthPrincipal me) {
        return users.findById(me.userId()).filter(u->me.tenantId().equals(u.getTenantId())).filter(u->"OWNER".equals(u.getRole())).isPresent();
    }
    private void requireOwner(AuthPrincipal me) { if(!owner(me)) throw BizException.of(ErrorCode.FORBIDDEN,"请由商家管理员订阅套餐或充值烟火"); }
}
