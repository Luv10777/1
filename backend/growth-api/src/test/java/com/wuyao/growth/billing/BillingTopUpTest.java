package com.wuyao.growth.billing;

import com.wuyao.growth.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BillingTopUpTest {
    private final BillingStore store=mock(BillingStore.class);
    private final AlipayGateway alipay=mock(AlipayGateway.class);
    private final BillingProperties config=new BillingProperties();
    private final BillingService billing=new BillingService(store,config,mock(WechatPayGateway.class),alipay,
        mock(ApplicationEventPublisher.class));

    @BeforeEach void setup() {
        TenantContext.set(7L);
        config.setPaymentMethod("ALIPAY");
        when(alipay.configured()).thenReturn(true);
        when(store.orderByKey(anyString())).thenReturn(Optional.empty());
    }
    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void topUpCreditsUseFivePerYuanForPresetsAndCustomAmounts() {
        for(long yuan:new long[]{500,1000,2000,5000,501,100000}) {
            var order=billing.topUp(new BillingDtos.TopUp("topup-"+yuan,yuan*100));
            assertThat(order.amountMinor()).isEqualTo(yuan*100);
            assertThat(order.credits()).isEqualTo(yuan*5);
        }
    }
    @Test void retryingAnExistingOrderKeepsItsOriginalCreditSnapshot() {
        var old=new BillingOrder();
        old.setTenantId(7L);old.setKind("TOPUP");old.setAmountMinor(50000);old.setCredits(5000);
        old.setExpiresAt(Instant.now().plusSeconds(600));old.setPaymentMethod("ALIPAY");
        when(store.orderByKey("old-order")).thenReturn(Optional.of(old));
        assertThat(billing.topUp(new BillingDtos.TopUp("old-order",50000)).credits()).isEqualTo(5000);
        verify(store,never()).save(any(BillingOrder.class));
    }
    @Test void topUpStillRequiresAtLeastFiveHundredWholeYuan() {
        for(long amount:new long[]{49900,50001,10000100})
            assertThatThrownBy(()->billing.topUp(new BillingDtos.TopUp("invalid",amount)))
                .hasMessageContaining("500 至 100,000 元的整数");
        verify(store,never()).lockAccount();
    }
    @Test void accountSummaryExposesTheRestoredUsagePricesAndNewTopUpRate() {
        var summary=billing.summary(true,false);
        assertThat(summary.creditsPerYuan()).isEqualTo(5);
        assertThat(summary.imageCredits()).isEqualTo(3);
        assertThat(summary.videoAnalysisCredits()).isEqualTo(1);
        assertThat(summary.liveCreditsPerHour()).isEqualTo(35);
        assertThat(BillingService.VIDEO_PLAN_CREDITS).isEqualTo(1);
        assertThat(BillingService.IMAGE_INSPIRATION_CREDITS).isEqualTo(1);
        assertThat(BillingService.COPY_CREDITS).isEqualTo(1);
    }
}
