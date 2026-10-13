package com.wuyao.growth.billing;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** API processes serve requests; only workers run reconciliation and failed-task recovery. */
@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="growth.worker.enabled",havingValue="true")
public class BillingRecoveryScheduler {
    private final BillingRecovery recovery;

    @Scheduled(fixedDelayString="${growth.billing.recovery-interval-ms:60000}")
    public void recover() { recovery.recover(); }
}
