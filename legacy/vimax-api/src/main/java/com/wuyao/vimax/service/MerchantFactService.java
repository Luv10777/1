package com.wuyao.vimax.service;

import com.wuyao.vimax.entity.MerchantFact;
import com.wuyao.vimax.repository.MerchantFactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商家事实服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MerchantFactService {

    private final MerchantFactRepository factRepository;

    /**
     * 创建商家事实
     */
    @Transactional
    public MerchantFact createFact(MerchantFact fact) {
        log.info("创建商家事实：merchantId={}, factType={}, factKey={}",
                fact.getMerchantId(), fact.getFactType(), fact.getFactKey());
        return factRepository.save(fact);
    }

    /**
     * 获取商家的所有生效事实
     */
    public List<MerchantFact> getEffectiveFacts(Long merchantId) {
        return factRepository.findEffectiveFacts(merchantId, LocalDateTime.now());
    }

    /**
     * 获取商家的所有事实
     */
    public List<MerchantFact> getAllFacts(Long merchantId) {
        return factRepository.findByMerchantIdAndStatus(merchantId, "ACTIVE");
    }

    /**
     * 更新商家事实
     */
    @Transactional
    public MerchantFact updateFact(Long factId, MerchantFact updates) {
        MerchantFact existing = factRepository.findById(factId)
                .orElseThrow(() -> new IllegalArgumentException("事实不存在: " + factId));

        if (updates.getFactValue() != null) {
            existing.setFactValue(updates.getFactValue());
        }
        if (updates.getIsCritical() != null) {
            existing.setIsCritical(updates.getIsCritical());
        }
        if (updates.getExpiresAt() != null) {
            existing.setExpiresAt(updates.getExpiresAt());
        }

        return factRepository.save(existing);
    }

    /**
     * 删除商家事实（软删除）
     */
    @Transactional
    public void deleteFact(Long factId) {
        MerchantFact fact = factRepository.findById(factId)
                .orElseThrow(() -> new IllegalArgumentException("事实不存在: " + factId));
        fact.setStatus("DELETED");
        fact.setDeletedAt(LocalDateTime.now());
        factRepository.save(fact);
        log.info("商家事实已删除：factId={}", factId);
    }
}
