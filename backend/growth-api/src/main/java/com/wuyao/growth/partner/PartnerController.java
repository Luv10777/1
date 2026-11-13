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

/** 服务商后台的接口：只有服务商的登录账号能用，见 PartnerService.requirePartner。 */
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

    /** expiry：SOON7、SOON30、EXPIRED、NEVER；bdUserId 只对负责人有效，0 表示负责人直接管的。 */
    @GetMapping("/tenants")
    public ApiResponse<PartnerDtos.Page<PartnerDtos.TenantView>> tenants(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String expiry, @RequestParam(required = false) Long bdUserId,
            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.tenants(me.userId(), page, size, keyword, expiry, bdUserId));
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

    /** days：1（今天）、7、30、90；bdUserId 只对负责人有效，0 表示负责人直接管的。 */
    @GetMapping("/dashboard")
    public ApiResponse<PartnerDtos.Dashboard> dashboard(@RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) Long bdUserId, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(insights.dashboard(me.userId(), days, bdUserId));
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

    @PutMapping("/tenants/{tenantId}/bd")
    public ApiResponse<PartnerDtos.TenantView> assignBd(@PathVariable Long tenantId, @Valid @RequestBody PartnerDtos.AssignBd input,
            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.assignBd(me.userId(), tenantId, input.bdUserId()));
    }

    @GetMapping("/team")
    public ApiResponse<List<PartnerDtos.Member>> team(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.team(me.userId()));
    }

    @PostMapping("/team")
    public ApiResponse<List<PartnerDtos.Member>> addBd(@Valid @RequestBody PartnerDtos.UidRequest input, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.addBd(me.userId(), input.uid()));
    }

    /** transferTo：他名下的商户转给哪个 BD，0 表示回到负责人直接管。 */
    @DeleteMapping("/team/{userId}")
    public ApiResponse<List<PartnerDtos.Member>> removeBd(@PathVariable Long userId, @RequestParam long transferTo,
            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.removeBd(me.userId(), userId, transferTo));
    }

    /** createdBy 只对负责人有效：按生成人（负责人或某个 BD）筛。 */
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
