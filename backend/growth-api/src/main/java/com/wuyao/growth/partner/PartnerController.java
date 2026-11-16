package com.wuyao.growth.partner;

import com.wuyao.growth.billing.BillingDtos;
import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import com.wuyao.growth.common.web.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 服务商后台的接口：只有服务商的登录账号能用，各项操作再按成员权限核对，见 PartnerService.require。 */
@RestController
@RequestMapping("/api/partner")
@RequiredArgsConstructor
public class PartnerController {
    private final PartnerService service;
    private final PartnerInsights insights;

    @GetMapping("/me")
    public ApiResponse<PartnerDtos.Overview> me(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.overview(me.userId()));
    }

    /** 自己在团队里的称呼。 */
    @PutMapping("/me/name")
    public ApiResponse<PartnerDtos.Overview> displayName(@Valid @RequestBody PartnerDtos.DisplayName input, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.setDisplayName(me.userId(), input.displayName()));
    }

    /** 一方志站内用：当前账号是不是服务商成员。不是成员也正常返回（member 为 false）。 */
    @GetMapping("/membership")
    public ApiResponse<PartnerDtos.Membership> membership(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.membership(me.userId()));
    }

    /** expiry：SOON7、SOON30、EXPIRED、NEVER。 */
    @GetMapping("/tenants")
    public ApiResponse<PartnerDtos.Page<PartnerDtos.TenantView>> tenants(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String expiry, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.tenants(me.userId(), page, size, keyword, expiry));
    }

    @PostMapping("/tenants")
    public ApiResponse<PartnerDtos.TenantView> addTenant(@Valid @RequestBody PartnerDtos.UidRequest input, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.addTenant(me.userId(), input.uid()));
    }

    @PostMapping("/tenants/{tenantId}/credits")
    public ApiResponse<PartnerDtos.TenantView> transfer(@PathVariable Long tenantId, @Valid @RequestBody PartnerDtos.Transfer input,
            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.transfer(me.userId(), tenantId, input));
    }

    @GetMapping("/workbench")
    public ApiResponse<PartnerDtos.Workbench> workbench(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(insights.workbench(me.userId()));
    }

    /** days：1（今天）、7、30、90。 */
    @GetMapping("/dashboard")
    public ApiResponse<PartnerDtos.Dashboard> dashboard(@RequestParam(defaultValue = "30") int days, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(insights.dashboard(me.userId(), days));
    }

    @GetMapping("/lookup")
    public ApiResponse<PartnerDtos.Lookup> lookup(@RequestParam Long uid, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.lookup(me.userId(), uid));
    }

    @GetMapping("/tenants/{tenantId}")
    public ApiResponse<PartnerDtos.TenantDetail> tenant(@PathVariable Long tenantId, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.tenantDetail(me.userId(), tenantId));
    }

    @PostMapping("/tenants/{tenantId}/renewals")
    public ApiResponse<PartnerDtos.TenantView> renew(@PathVariable Long tenantId, @Valid @RequestBody PartnerDtos.Renew input,
            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.renew(me.userId(), tenantId, input));
    }

    /** 分配记录：有财务权限的看全部（可按商户、操作人筛），只有分配权限的只看自己分出去的。 */
    @GetMapping("/distributions")
    public ApiResponse<PartnerDtos.Page<PartnerDtos.Distribution>> distributions(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long actorUserId, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.distributions(me.userId(), page, size, tenantId, actorUserId));
    }

    @PostMapping("/distributions/{id}/retry")
    public ApiResponse<PartnerDtos.Distribution> retryDistribution(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.retryDistribution(me.userId(), id));
    }

    @GetMapping("/team")
    public ApiResponse<List<PartnerDtos.Member>> team(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.team(me.userId()));
    }

    @PostMapping("/team")
    public ApiResponse<List<PartnerDtos.Member>> addMember(@Valid @RequestBody PartnerDtos.NewMember input, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.addMember(me.userId(), input));
    }

    @PutMapping("/team/{userId}")
    public ApiResponse<List<PartnerDtos.Member>> updateMember(@PathVariable Long userId, @Valid @RequestBody PartnerDtos.UpdateMember input,
            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.updateMember(me.userId(), userId, input));
    }

    @DeleteMapping("/team/{userId}")
    public ApiResponse<List<PartnerDtos.Member>> removeMember(@PathVariable Long userId, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.removeMember(me.userId(), userId));
    }

    /** createdBy：按生成人筛。 */
    @GetMapping("/codes")
    public ApiResponse<PageResult<BillingDtos.RedeemCodeView>> codes(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) Long createdBy,
            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.codes(me.userId(), page, size, status, keyword, createdBy));
    }

    @PostMapping("/codes/{id}/void")
    public ApiResponse<BillingDtos.RedeemCodeView> voidCode(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.voidCode(me.userId(), id));
    }

    @PostMapping("/codes")
    public ApiResponse<List<BillingDtos.RedeemCodeView>> createCodes(@Valid @RequestBody PartnerDtos.NewCodes input,
            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.createCodes(me.userId(), input));
    }

    @GetMapping("/ledger")
    public ApiResponse<PartnerDtos.Page<PartnerDtos.LedgerEntry>> ledger(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.ledger(me.userId(), page, size));
    }
}
