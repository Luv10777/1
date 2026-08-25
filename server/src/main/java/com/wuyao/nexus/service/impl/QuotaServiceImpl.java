package com.wuyao.nexus.service.impl;

import com.wuyao.nexus.entity.TenantQuota;
import com.wuyao.nexus.exception.BusinessException;
import com.wuyao.nexus.repository.TenantQuotaRepository;
import com.wuyao.nexus.service.QuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaServiceImpl implements QuotaService {

    private final TenantQuotaRepository quotaRepository;

    @Override
    @Transactional
    public void checkAndReserve(Long tenantId, BigDecimal amount) {
        TenantQuota quota = getOrCreateQuota(tenantId);

        // 检查配额是否足够
        BigDecimal available = quota.getAvailableCredits();
        if (available.compareTo(amount) < 0) {
            throw new BusinessException("配额不足，可用: " + available + ", 需要: " + amount);
        }

        // 预留配额
        quota.setReservedCredits(quota.getReservedCredits().add(amount));
        quotaRepository.save(quota);

        log.info("已预留配额: tenantId={}, amount={}", tenantId, amount);
    }

    @Override
    @Transactional
    public void charge(Long tenantId, BigDecimal amount) {
        TenantQuota quota = quotaRepository.findByTenantIdForUpdate(tenantId)
                .orElseThrow(() -> new BusinessException("配额不存在"));

        // 从预留中扣除
        quota.setReservedCredits(quota.getReservedCredits().subtract(amount));

        // 增加已使用
        quota.setUsedCredits(quota.getUsedCredits().add(amount));

        quotaRepository.save(quota);

        log.info("已扣除配额: tenantId={}, amount={}", tenantId, amount);
    }

    @Override
    @Transactional
    public void release(Long tenantId, BigDecimal amount) {
        TenantQuota quota = quotaRepository.findByTenantIdForUpdate(tenantId)
                .orElseThrow(() -> new BusinessException("配额不存在"));

        // 释放预留
        quota.setReservedCredits(quota.getReservedCredits().subtract(amount));

        quotaRepository.save(quota);

        log.info("已释放配额: tenantId={}, amount={}", tenantId, amount);
    }

    @Override
    @Transactional
    public void recharge(Long tenantId, BigDecimal amount) {
        TenantQuota quota = getOrCreateQuota(tenantId);

        quota.setTotalCredits(quota.getTotalCredits().add(amount));

        quotaRepository.save(quota);

        log.info("配额充值成功: tenantId={}, amount={}", tenantId, amount);
    }

    @Override
    public Object getQuotaInfo(Long tenantId) {
        TenantQuota quota = getOrCreateQuota(tenantId);

        // 重置每日配额
        if (!quota.getCurrentDate().equals(LocalDate.now())) {
            resetDailyQuota(quota);
        }

        Map<String, Object> info = new HashMap<>();
        info.put("totalCredits", quota.getTotalCredits());
        info.put("usedCredits", quota.getUsedCredits());
        info.put("reservedCredits", quota.getReservedCredits());
        info.put("availableCredits", quota.getAvailableCredits());
        info.put("textQuotaPerDay", quota.getTextQuotaPerDay());
        info.put("textUsedToday", quota.getTextUsedToday());
        info.put("imageQuotaPerDay", quota.getImageQuotaPerDay());
        info.put("imageUsedToday", quota.getImageUsedToday());
        info.put("videoQuotaPerDay", quota.getVideoQuotaPerDay());
        info.put("videoUsedToday", quota.getVideoUsedToday());

        return info;
    }

    private TenantQuota getOrCreateQuota(Long tenantId) {
        return quotaRepository.findByTenantId(tenantId)
                .orElseGet(() -> {
                    TenantQuota quota = new TenantQuota();
                    quota.setTenantId(tenantId);
                    quota.setTotalCredits(new BigDecimal("100.00")); // 新租户赠送100元
                    return quotaRepository.save(quota);
                });
    }

    @Transactional
    protected void resetDailyQuota(TenantQuota quota) {
        quota.setCurrentDate(LocalDate.now());
        quota.setTextUsedToday(0);
        quota.setImageUsedToday(0);
        quota.setVideoUsedToday(0);
        quotaRepository.save(quota);

        log.info("已重置每日配额: tenantId={}", quota.getTenantId());
    }
}
