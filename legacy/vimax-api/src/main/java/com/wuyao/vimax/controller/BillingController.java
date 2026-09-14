package com.wuyao.vimax.controller;

import com.wuyao.vimax.dto.ApiResponse;
import com.wuyao.vimax.service.billing.BillingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * 账本和额度 Controller
 *
 * Phase 7: 前端集成API
 */
@RestController
@RequestMapping("/billing")
@RequiredArgsConstructor
@Slf4j
public class BillingController {

    private final BillingService billingService;

    /**
     * 获取租户余额
     */
    @GetMapping("/balance")
    public ApiResponse<BigDecimal> getBalance(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "1") Long tenantId) {

        log.info("获取余额: tenantId={}", tenantId);

        BigDecimal balance = billingService.getTenantBalance(tenantId);
        return ApiResponse.success(balance);
    }

    /**
     * 充值
     */
    @PostMapping("/recharge")
    public ApiResponse<String> recharge(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "1") Long tenantId,
            @RequestParam BigDecimal amount,
            @RequestParam(defaultValue = "MANUAL") String paymentMethod) {

        log.info("充值: tenantId={}, amount={}", tenantId, amount);

        billingService.recharge(tenantId, amount, paymentMethod);
        return ApiResponse.success("充值成功");
    }

    /**
     * 检查额度是否足够
     */
    @GetMapping("/check")
    public ApiResponse<Boolean> checkCredits(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "1") Long tenantId,
            @RequestParam BigDecimal amount) {

        log.info("检查额度: tenantId={}, amount={}", tenantId, amount);

        boolean hasEnough = billingService.hasEnoughCredits(tenantId, amount);
        return ApiResponse.success(hasEnough);
    }
}
