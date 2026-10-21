package com.wuyao.growth.alliance;

import com.wuyao.growth.billing.BillingDtos;
import com.wuyao.growth.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** 领券商户的额度：会员开通时按套餐发放，一个会员期一条，期满作废、不累积。 */
@Service
@RequiredArgsConstructor
public class AllianceQuotaService {
    private final AllianceQuotaGrantRepository grants;
    private final AllianceProperties config;

    /**
     * 与会员订单入账同一事务、同一租户里同步执行：入账回滚，额度也不会留下。
     * 账单订单号唯一，同一张订单重复入账不会重复发额度。
     */
    @EventListener
    @Transactional
    public void onMembershipActivated(BillingDtos.MembershipActivated event) {
        long amount = event.plan().allianceQuotaMinor();
        if (amount <= 0 || grants.existsByBillingOrderId(event.billingOrderId())) return;
        var grant = new AllianceQuotaGrant();
        grant.setTenantId(event.tenantId());
        grant.setBillingOrderId(event.billingOrderId());
        grant.setPlanCode(event.plan().code());
        grant.setPlanName(event.plan().name());
        grant.setAmountMinor(amount);
        grant.setValidFrom(event.periodStart());
        grant.setValidUntil(event.periodEnd());
        grants.save(grant);
    }

    @Transactional(readOnly = true)
    public AllianceDtos.QuotaView quota() {
        Instant now = Instant.now();
        List<AllianceQuotaGrant> open = grants.currentAndUpcoming(TenantContext.require(), now);
        AllianceDtos.QuotaPeriod current = open.stream().filter(g -> !g.getValidFrom().isAfter(now))
                .reduce((first, second) -> second).map(AllianceDtos.QuotaPeriod::of).orElse(null);
        List<AllianceDtos.QuotaPeriod> upcoming = open.stream().filter(g -> g.getValidFrom().isAfter(now))
                .map(AllianceDtos.QuotaPeriod::of).toList();
        return new AllianceDtos.QuotaView(current, upcoming, config.getPerSupplierLimit());
    }
}
