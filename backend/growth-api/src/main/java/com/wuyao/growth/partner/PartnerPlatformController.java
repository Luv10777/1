package com.wuyao.growth.partner;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 平台管理服务商（管理后台「服务商」页），只开放给计费运营名单。 */
@RestController
@RequestMapping("/api/platform/partners")
@RequiredArgsConstructor
public class PartnerPlatformController {
    private final PartnerPlatformService service;

    @GetMapping
    public ApiResponse<List<PartnerDtos.PlatformPartner>> list(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.list(me.userId()));
    }

    @PostMapping
    public ApiResponse<PartnerDtos.PlatformPartner> create(@Valid @RequestBody PartnerDtos.NewPartner input, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.create(input, me.userId()));
    }

    @PostMapping("/{id}/members")
    public ApiResponse<PartnerDtos.PlatformPartner> addMember(@PathVariable Long id, @Valid @RequestBody PartnerDtos.UidRequest input,
            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.addMember(id, input.uid(), me.userId()));
    }

    @DeleteMapping("/{id}/members/{userId}")
    public ApiResponse<PartnerDtos.PlatformPartner> removeMember(@PathVariable Long id, @PathVariable Long userId, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.removeMember(id, userId, me.userId()));
    }

    @PutMapping("/{id}")
    public ApiResponse<PartnerDtos.PlatformPartner> rename(@PathVariable Long id, @Valid @RequestBody PartnerDtos.Rename input,
            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.rename(id, input, me.userId()));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<PartnerDtos.PlatformPartner> status(@PathVariable Long id, @Valid @RequestBody PartnerDtos.SetStatus input,
            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.setStatus(id, input.status(), me.userId()));
    }

    @PostMapping("/{id}/credits")
    public ApiResponse<PartnerDtos.PlatformPartner> grant(@PathVariable Long id, @Valid @RequestBody PartnerDtos.Grant input,
            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.grant(id, input, me.userId()));
    }

    @PutMapping("/{id}/quotas")
    public ApiResponse<PartnerDtos.PlatformPartner> quota(@PathVariable Long id, @Valid @RequestBody PartnerDtos.SetQuota input,
            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.setQuota(id, input, me.userId()));
    }

    @GetMapping("/{id}/tenants")
    public ApiResponse<PartnerDtos.Page<PartnerDtos.TenantView>> tenants(@PathVariable Long id, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String keyword, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.tenants(id, page, size, keyword, me.userId()));
    }

    @GetMapping("/{id}/ledger")
    public ApiResponse<PartnerDtos.Page<PartnerDtos.LedgerEntry>> ledger(@PathVariable Long id, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.ledger(id, page, size, me.userId()));
    }

    /** 按 UID 查商户现在的归属。 */
    @GetMapping("/lookup")
    public ApiResponse<Map<String, Object>> lookup(@RequestParam Long uid, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.lookup(uid, me.userId()));
    }

    /** 改某个商户的归属；body 里 partnerId 为空表示解除归属。 */
    @PutMapping("/assignments/{tenantId}")
    public ApiResponse<Void> reassign(@PathVariable Long tenantId, @Valid @RequestBody PartnerDtos.Reassign input, @AuthenticationPrincipal AuthPrincipal me) {
        service.reassign(tenantId, input.partnerId(), me.userId());
        return ApiResponse.ok(null);
    }
}
