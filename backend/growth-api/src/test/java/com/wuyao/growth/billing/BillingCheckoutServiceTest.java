package com.wuyao.growth.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BillingCheckoutServiceTest {
    private final BillingService billing=mock(BillingService.class);
    private final WechatPayGateway wechat=mock(WechatPayGateway.class);
    private final BillingCheckoutService checkout=new BillingCheckoutService(billing,wechat);
    private final ObjectMapper json=new ObjectMapper();

    @AfterEach void clearTenant() { TenantContext.clear(); }
    private BillingDtos.OrderView order(String state) {
        return new BillingDtos.OrderView(UUID.randomUUID().toString(),"TOPUP",100,10,state,null,
            Instant.now(),Instant.now().plusSeconds(600),null,null,"WECHAT",null);
    }
    @Test void aTimedOutRemoteCreateCanBeRecoveredUsingTheSameOrder() {
        TenantContext.set(7L);
        var order=order("PENDING_PAYMENT");
        when(billing.order(order.id())).thenReturn(order);
        when(wechat.query(order.id())).thenReturn(json.createObjectNode().put("code","ORDER_NOT_EXIST"));
        when(wechat.create(order,7L)).thenReturn("weixin://wxpay/test");
        when(billing.savePaymentCode(order.id(),"weixin://wxpay/test")).thenReturn(order);
        assertThat(checkout.refresh(order.id())).isSameAs(order);
        verify(wechat).create(order,7L);
        verify(billing).savePaymentCode(order.id(),"weixin://wxpay/test");
        verify(billing,never()).topUp(any());
    }
    @Test void missingExpiredOrderIsClosedLocallyWithoutCreatingAnotherRemoteOrder() {
        var order=order("EXPIRED");
        when(billing.order(order.id())).thenReturn(order);
        when(wechat.query(order.id())).thenReturn(json.createObjectNode().put("code","ORDER_NOT_EXIST"));
        when(billing.cancelOrder(order.id())).thenReturn(order);
        checkout.refresh(order.id());
        verify(billing).cancelOrder(order.id());
        verify(wechat,never()).create(any(),anyLong());
        verify(wechat,never()).close(anyString());
    }
    @Test void paidRemoteOrderIsFulfilledBeforeAttemptingCancellation() {
        var order=order("PENDING_PAYMENT");
        var proof=json.createObjectNode().put("trade_state","SUCCESS");
        when(billing.order(order.id())).thenReturn(order);
        when(wechat.query(order.id())).thenReturn(proof);
        when(billing.confirmWechat(order.id(),proof)).thenReturn(order);
        checkout.cancel(order.id());
        verify(billing).confirmWechat(order.id(),proof);
        verify(billing,never()).cancelOrder(anyString());
        verify(wechat,never()).close(anyString());
    }
    @Test void unverifiedNetworkErrorsCannotCancelOrRecreateAnOrder() {
        var order=order("PENDING_PAYMENT");
        when(billing.order(order.id())).thenReturn(order);
        when(wechat.query(order.id())).thenThrow(new IllegalStateException("network"));
        assertThatThrownBy(()->checkout.refresh(order.id())).hasMessage("network");
        assertThatThrownBy(()->checkout.cancel(order.id())).hasMessage("network");
        verify(billing,never()).cancelOrder(anyString());
        verify(wechat,never()).create(any(),anyLong());
    }
}
