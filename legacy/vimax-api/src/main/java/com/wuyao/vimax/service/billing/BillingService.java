package com.wuyao.vimax.service.billing;

import com.wuyao.vimax.entity.TenantCreditAccount;
import com.wuyao.vimax.entity.CreditTransaction;
import com.wuyao.vimax.repository.TenantCreditAccountRepository;
import com.wuyao.vimax.repository.CreditTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 账本和额度服务
 *
 * Phase 4: 租户额度管理、成本记账
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BillingService {

    private final TenantCreditAccountRepository accountRepository;
    private final CreditTransactionRepository transactionRepository;

    /**
     * 获取租户余额
     */
    public BigDecimal getTenantBalance(Long tenantId) {
        TenantCreditAccount account = accountRepository.findByTenantId(tenantId)
            .orElseThrow(() -> new IllegalArgumentException("租户账户不存在"));

        return account.getAvailableCredits();
    }

    /**
     * 检查额度是否足够
     */
    public boolean hasEnoughCredits(Long tenantId, BigDecimal amount) {
        BigDecimal balance = getTenantBalance(tenantId);
        return balance.compareTo(amount) >= 0;
    }

    /**
     * 预占额度
     */
    @Transactional
    public void reserveCredits(Long tenantId, BigDecimal amount, String referenceType, Long referenceId) {
        log.info("预占额度: tenantId={}, amount={}", tenantId, amount);

        TenantCreditAccount account = accountRepository.findByTenantId(tenantId)
            .orElseThrow(() -> new IllegalArgumentException("租户账户不存在"));

        if (!hasEnoughCredits(tenantId, amount)) {
            throw new IllegalStateException("余额不足");
        }

        // 扣减可用额度，增加预占额度
        account.setAvailableCredits(account.getAvailableCredits().subtract(amount));
        account.setReservedCredits(account.getReservedCredits().add(amount));
        accountRepository.save(account);

        // 记录交易
        createTransaction(tenantId, "RESERVE", amount.negate(), referenceType, referenceId, "预占额度");
    }

    /**
     * 释放预占额度
     */
    @Transactional
    public void releaseCredits(Long tenantId, BigDecimal amount, String referenceType, Long referenceId) {
        log.info("释放预占额度: tenantId={}, amount={}", tenantId, amount);

        TenantCreditAccount account = accountRepository.findByTenantId(tenantId)
            .orElseThrow(() -> new IllegalArgumentException("租户账户不存在"));

        // 增加可用额度，减少预占额度
        account.setAvailableCredits(account.getAvailableCredits().add(amount));
        account.setReservedCredits(account.getReservedCredits().subtract(amount));
        accountRepository.save(account);

        // 记录交易
        createTransaction(tenantId, "RELEASE", amount, referenceType, referenceId, "释放预占额度");
    }

    /**
     * 消费额度（从预占转为实际消费）
     */
    @Transactional
    public void consumeCredits(Long tenantId, BigDecimal amount, String referenceType, Long referenceId) {
        log.info("消费额度: tenantId={}, amount={}", tenantId, amount);

        TenantCreditAccount account = accountRepository.findByTenantId(tenantId)
            .orElseThrow(() -> new IllegalArgumentException("租户账户不存在"));

        // 减少预占额度，增加已用额度
        account.setReservedCredits(account.getReservedCredits().subtract(amount));
        account.setUsedCredits(account.getUsedCredits().add(amount));
        accountRepository.save(account);

        // 记录交易
        createTransaction(tenantId, "CONSUME", amount.negate(), referenceType, referenceId, "消费额度");
    }

    /**
     * 充值
     */
    @Transactional
    public void recharge(Long tenantId, BigDecimal amount, String paymentMethod) {
        log.info("充值: tenantId={}, amount={}", tenantId, amount);

        TenantCreditAccount account = accountRepository.findByTenantId(tenantId)
            .orElseThrow(() -> new IllegalArgumentException("租户账户不存在"));

        // 增加总额度和可用额度
        account.setTotalCredits(account.getTotalCredits().add(amount));
        account.setAvailableCredits(account.getAvailableCredits().add(amount));
        accountRepository.save(account);

        // 记录交易
        createTransaction(tenantId, "RECHARGE", amount, "PAYMENT", null, "充值");
    }

    /**
     * 创建交易记录
     */
    private void createTransaction(Long tenantId, String transactionType, BigDecimal amount,
                                   String referenceType, Long referenceId, String description) {
        CreditTransaction transaction = new CreditTransaction();
        transaction.setTenantId(tenantId);
        transaction.setTransactionType(transactionType);
        transaction.setAmount(amount);
        transaction.setReferenceType(referenceType);
        transaction.setReferenceId(referenceId);
        transaction.setDescription(description);
        transaction.setTransactionTime(LocalDateTime.now());

        transactionRepository.save(transaction);
    }
}
