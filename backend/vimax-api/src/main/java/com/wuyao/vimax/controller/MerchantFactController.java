package com.wuyao.vimax.controller;

import com.wuyao.vimax.dto.ApiResponse;
import com.wuyao.vimax.dto.CreateMerchantFactRequest;
import com.wuyao.vimax.entity.MerchantFact;
import com.wuyao.vimax.service.MerchantFactService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 商家事实 Controller
 */
@RestController
@RequestMapping("/merchants/{merchantId}/facts")
@RequiredArgsConstructor
@Slf4j
public class MerchantFactController {

    private final MerchantFactService factService;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /**
     * 创建商家事实
     */
    @PostMapping
    public ApiResponse<MerchantFact> createFact(
            @PathVariable Long merchantId,
            @Valid @RequestBody CreateMerchantFactRequest request,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "1") Long userId) {

        log.info("创建商家事实：merchantId={}, factType={}, factKey={}",
                merchantId, request.getFactType(), request.getFactKey());

        MerchantFact fact = new MerchantFact();
        fact.setMerchantId(merchantId);
        fact.setFactType(request.getFactType());
        fact.setFactKey(request.getFactKey());
        fact.setFactValue(request.getFactValue());
        fact.setSource(request.getFactSource());
        fact.setIsCritical(request.getPriority() != null && request.getPriority() > 50);

        if (request.getEffectiveFrom() != null) {
            fact.setExpiresAt(LocalDateTime.parse(request.getEffectiveFrom(), FORMATTER));
        }
        if (request.getEffectiveTo() != null) {
            fact.setExpiresAt(LocalDateTime.parse(request.getEffectiveTo(), FORMATTER));
        }

        MerchantFact created = factService.createFact(fact);
        return ApiResponse.success("事实创建成功", created);
    }

    /**
     * 获取商家的所有生效事实
     */
    @GetMapping
    public ApiResponse<List<MerchantFact>> getFacts(
            @PathVariable Long merchantId,
            @RequestParam(required = false, defaultValue = "true") Boolean onlyEffective) {

        log.info("查询商家事实：merchantId={}, onlyEffective={}", merchantId, onlyEffective);

        List<MerchantFact> facts = onlyEffective
                ? factService.getEffectiveFacts(merchantId)
                : factService.getAllFacts(merchantId);

        return ApiResponse.success(facts);
    }

    /**
     * 更新商家事实
     */
    @PutMapping("/{factId}")
    public ApiResponse<MerchantFact> updateFact(
            @PathVariable Long merchantId,
            @PathVariable Long factId,
            @RequestBody CreateMerchantFactRequest request,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "1") Long userId) {

        log.info("更新商家事实：merchantId={}, factId={}", merchantId, factId);

        MerchantFact updates = new MerchantFact();
        updates.setFactValue(request.getFactValue());
        updates.setIsCritical(request.getPriority() != null && request.getPriority() > 50);

        if (request.getEffectiveTo() != null) {
            updates.setExpiresAt(LocalDateTime.parse(request.getEffectiveTo(), FORMATTER));
        }

        MerchantFact updated = factService.updateFact(factId, updates);
        return ApiResponse.success("事实更新成功", updated);
    }

    /**
     * 删除商家事实
     */
    @DeleteMapping("/{factId}")
    public ApiResponse<Void> deleteFact(
            @PathVariable Long merchantId,
            @PathVariable Long factId) {

        log.info("删除商家事实：merchantId={}, factId={}", merchantId, factId);

        factService.deleteFact(factId);
        return ApiResponse.success("事实删除成功", null);
    }
}
