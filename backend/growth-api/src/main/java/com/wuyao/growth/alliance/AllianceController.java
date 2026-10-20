package com.wuyao.growth.alliance;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import com.wuyao.growth.common.web.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 商户使用的联盟券接口：领券方的额度、券库、我的联盟券，供券方的供券单和结算单。 */
@RestController
@RequestMapping("/api/alliance")
@RequiredArgsConstructor
public class AllianceController {
    private final AllianceQuotaService quota;
    private final AllianceVoucherService vouchers;
    private final AllianceClaimService claims;
    private final AllianceOfferService offers;
    private final AllianceSettlementService settlements;
    private final AllianceProperties config;

    @GetMapping("/rules")
    public ApiResponse<AllianceDtos.Rules> rules() {
        return ApiResponse.ok(AllianceDtos.Rules.of(config));
    }

    @GetMapping("/quota")
    public ApiResponse<AllianceDtos.QuotaView> quota() {
        return ApiResponse.ok(quota.quota());
    }

    @GetMapping("/pool")
    public ApiResponse<PageResult<AllianceDtos.PoolItem>> pool(@RequestParam(required = false) String keyword,
                                                               @RequestParam(required = false) String city,
                                                               @RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "12") int size) {
        return ApiResponse.ok(vouchers.pool(keyword, city, page, size));
    }

    @PostMapping("/offers/{offerId}/claims")
    public ApiResponse<AllianceDtos.VoucherView> claim(@PathVariable Long offerId, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(claims.claim(offerId, me.userId()));
    }

    @GetMapping("/vouchers")
    public ApiResponse<PageResult<AllianceDtos.VoucherView>> vouchers(@RequestParam(defaultValue = "0") int page,
                                                                       @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(vouchers.mine(page, size));
    }

    @GetMapping("/vouchers/{ref}")
    public ApiResponse<AllianceDtos.VoucherView> voucher(@PathVariable UUID ref) {
        return ApiResponse.ok(vouchers.detail(ref));
    }

    @PostMapping("/vouchers/{ref}/dispute")
    public ApiResponse<AllianceDtos.VoucherView> dispute(@PathVariable UUID ref,
                                                         @Valid @RequestBody AllianceDtos.DisputeRequest request,
                                                         @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(vouchers.dispute(ref, request.reason(), me.userId()));
    }

    @PostMapping("/offers")
    public ApiResponse<AllianceDtos.OfferView> createOffer(@Valid @RequestBody AllianceDtos.OfferRequest request,
                                                           @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(offers.create(request, me.userId()));
    }

    @GetMapping("/offers")
    public ApiResponse<List<AllianceDtos.OfferView>> myOffers() {
        return ApiResponse.ok(offers.mine());
    }

    @PostMapping("/offers/{id}/withdraw")
    public ApiResponse<AllianceDtos.OfferView> withdraw(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(offers.withdraw(id, me.userId()));
    }

    @GetMapping("/settlements")
    public ApiResponse<PageResult<AllianceDtos.SettlementView>> settlements(@RequestParam(defaultValue = "0") int page,
                                                                             @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(settlements.mine(page, size));
    }
}
