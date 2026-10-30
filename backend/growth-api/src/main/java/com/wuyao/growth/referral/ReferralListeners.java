package com.wuyao.growth.referral;

import com.wuyao.growth.billing.BillingDtos;
import com.wuyao.growth.iam.dto.AuthDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
class ReferralListeners {
    private final ReferralService service;

    /** 注册提交后再绑定；绑定失败只影响归属（落到公司），不影响注册本身。 */
    @TransactionalEventListener(fallbackExecution = true)
    void onRegistered(AuthDtos.UserRegistered event) {
        try {
            service.bind(event.tenantId(), event.phone(), event.inviteCode());
        } catch (RuntimeException e) {
            log.error("邀请注册归属失败: inviteeTenant={}", event.tenantId(), e);
        }
    }

    /** 与订单入账同一事务：登记返利任务失败会让这次入账一起回滚重试，不会漏记。 */
    @EventListener
    void onOrderPaid(BillingDtos.OrderPaid event) {
        service.onOrderPaid(event.tenantId(), event.billingOrderId(), event.kind(), event.amountMinor());
    }
}
