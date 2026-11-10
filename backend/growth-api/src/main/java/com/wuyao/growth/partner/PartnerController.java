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

    @GetMapping("/me")
    public ApiResponse<PartnerDtos.Overview> me(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.overview(me.userId()));
    }

    @GetMapping("/tenants")
    public ApiResponse<PartnerDtos.Page<PartnerDtos.TenantView>> tenants(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String keyword,
            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.tenants(me.userId(), page, size, keyword));
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

    @GetMapping("/codes")
    public ApiResponse<PageResult<BillingDtos.RedeemCodeView>> codes(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.codes(me.userId(), page, size, status, keyword));
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
