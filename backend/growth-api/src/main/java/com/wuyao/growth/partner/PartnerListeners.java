package com.wuyao.growth.partner;

import com.wuyao.growth.billing.BillingDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class PartnerListeners {
    private final PartnerStore store;

    /**
     * 与兑换同一事务：兑换了服务商生成的激活码，商户还没有归属时归到这家服务商，码是 BD 生成的就归到这个 BD；
     * 已归属的不改（第一次为准）。
     */
    @EventListener
    void onPartnerCodeRedeemed(BillingDtos.PartnerCodeRedeemed event) {
        Long bd = store.codeCreator(event.codeId())
                .filter(creator -> store.role(event.partnerId(), creator).filter("BD"::equals).isPresent()).orElse(null);
        store.bindIfAbsent(event.tenantId(), event.partnerId(), "CODE", null, bd);
    }
}
