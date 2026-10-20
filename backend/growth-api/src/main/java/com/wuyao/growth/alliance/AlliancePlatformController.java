package com.wuyao.growth.alliance;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import com.wuyao.growth.community.PlatformManagementService;
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

/** 平台运营的联盟券接口：供券审核、申诉处理、结算标记和汇总。只对平台运营开放。 */
@RestController
@RequestMapping("/api/platform")
@RequiredArgsConstructor
public class AlliancePlatformController {
    private final AlliancePlatformService platform;
    private final PlatformManagementService management;

    @GetMapping("/alliance/offers")
    public ApiResponse<List<AllianceDtos.PlatformOffer>> pendingOffers(@AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        return ApiResponse.ok(platform.pendingOffers());
    }

    @PostMapping("/tenants/{tenantId}/alliance/offers/{id}/review")
    public ApiResponse<AllianceDtos.OfferView> review(@PathVariable Long tenantId, @PathVariable Long id,
                                                      @Valid @RequestBody AllianceDtos.ReviewRequest request,
                                                      @AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        return ApiResponse.ok(platform.review(tenantId, id, request, me.userId()));
    }

    @GetMapping("/alliance/disputes")
    public ApiResponse<List<AllianceDtos.PlatformDispute>> disputes(@AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        return ApiResponse.ok(platform.disputes());
    }

    @PostMapping("/tenants/{tenantId}/alliance/vouchers/{ref}/dispute-resolution")
    public ApiResponse<Void> resolve(@PathVariable Long tenantId, @PathVariable UUID ref,
                                     @Valid @RequestBody AllianceDtos.DisputeResolution request,
                                     @AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        platform.resolveDispute(tenantId, ref, request);
        return ApiResponse.ok();
    }

    @GetMapping("/alliance/accounts")
    public ApiResponse<List<AllianceDtos.PlatformAccount>> accounts(@RequestParam(required = false) String status,
                                                                   @AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        return ApiResponse.ok(platform.accounts(status));
    }

    @PostMapping("/tenants/{tenantId}/alliance/settlement-account/review")
    public ApiResponse<AllianceDtos.AccountView> reviewAccount(@PathVariable Long tenantId,
                                                               @Valid @RequestBody AllianceDtos.AccountReviewRequest request,
                                                               @AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        return ApiResponse.ok(platform.reviewAccount(tenantId, request, me.userId()));
    }

    @PostMapping("/tenants/{tenantId}/alliance/settlement-account/reveal")
    public ApiResponse<AllianceDtos.RevealView> revealAccount(@PathVariable Long tenantId,
                                                              @Valid @RequestBody AllianceDtos.RevealRequest request,
                                                              @AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        return ApiResponse.ok(platform.revealAccount(tenantId, request.purpose(), me.userId()));
    }

    @GetMapping("/alliance/statements")
    public ApiResponse<List<AllianceDtos.PlatformStatement>> statements(@RequestParam(required = false) String status,
                                                                       @RequestParam(required = false) String period,
                                                                       @AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        return ApiResponse.ok(platform.statements(status, period));
    }

    @GetMapping("/tenants/{tenantId}/alliance/statements/{id}")
    public ApiResponse<AllianceDtos.StatementDetail> statement(@PathVariable Long tenantId, @PathVariable Long id,
                                                               @AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        return ApiResponse.ok(platform.statement(tenantId, id));
    }

    @PostMapping("/tenants/{tenantId}/alliance/statements/{id}/paid")
    public ApiResponse<AllianceDtos.StatementView> statementPaid(@PathVariable Long tenantId, @PathVariable Long id,
                                                                 @Valid @RequestBody AllianceDtos.StatementPaidRequest request,
                                                                 @AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        return ApiResponse.ok(platform.statementPaid(tenantId, id, request, me.userId()));
    }

    @PostMapping("/tenants/{tenantId}/alliance/statements/{id}/void")
    public ApiResponse<AllianceDtos.StatementView> voidStatement(@PathVariable Long tenantId, @PathVariable Long id,
                                                                 @Valid @RequestBody AllianceDtos.StatementNoteRequest request,
                                                                 @AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        return ApiResponse.ok(platform.voidStatement(tenantId, id, request.note(), me.userId()));
    }

    @PostMapping("/tenants/{tenantId}/alliance/statements/{id}/resolve")
    public ApiResponse<AllianceDtos.StatementView> resolveStatement(@PathVariable Long tenantId, @PathVariable Long id,
                                                                    @Valid @RequestBody AllianceDtos.StatementNoteRequest request,
                                                                    @AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        return ApiResponse.ok(platform.resolveStatement(tenantId, id, request.note(), me.userId()));
    }

    @PostMapping("/tenants/{tenantId}/alliance/statements/{id}/reveal")
    public ApiResponse<AllianceDtos.RevealView> revealPayee(@PathVariable Long tenantId, @PathVariable Long id,
                                                            @Valid @RequestBody AllianceDtos.RevealRequest request,
                                                            @AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        return ApiResponse.ok(platform.revealStatementPayee(tenantId, id, request.purpose(), me.userId()));
    }

    /** period 形如 2026-09，只能是已经结束的月份。 */
    @PostMapping("/alliance/statements/generate")
    public ApiResponse<java.util.Map<String, Integer>> generate(@RequestParam String period,
                                                                @AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        java.time.YearMonth month;
        try {
            month = java.time.YearMonth.parse(period);
        } catch (java.time.format.DateTimeParseException e) {
            throw com.wuyao.growth.common.web.BizException.of(com.wuyao.growth.common.web.ErrorCode.BAD_REQUEST, "月份格式应为 2026-09");
        }
        return ApiResponse.ok(java.util.Map.of("created", platform.generateStatements(month)));
    }

    @GetMapping("/alliance/summary")
    public ApiResponse<AllianceDtos.Summary> summary(@AuthenticationPrincipal AuthPrincipal me) {
        management.requireOperator(me);
        return ApiResponse.ok(platform.summary());
    }
}
