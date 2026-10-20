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
    private final AlipayGateway alipay=mock(AlipayGateway.class);
    private final BillingCheckoutService checkout=new BillingCheckoutService(billing,wechat,alipay);
    private final ObjectMapper json=new ObjectMapper();

    @AfterEach void clearTenant() { TenantContext.clear(); }
    private BillingDtos.OrderView order(String state) { return order(state,"WECHAT"); }
    private BillingDtos.OrderView order(String state,String method) {
        return new BillingDtos.OrderView(UUID.randomUUID().toString(),"TOPUP",100,10,state,null,
            Instant.now(),Instant.now().plusSeconds(600),null,null,method,null,null,null);
    }
    private com.fasterxml.jackson.databind.node.ObjectNode missingTrade() {
        return json.createObjectNode().put("code","40004").put("sub_code","ACQ.TRADE_NOT_EXIST");
    }
    @Test void aTimedOutRemoteCreateCanBeRecoveredUsingTheSameOrder() {
        TenantContext.set(7L);
        var order=order("PENDING_PAYMENT");
        when(billing.order(order.id())).thenReturn(order);
        when(billing.claimReconciliation(order.id())).thenReturn(true);
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
        when(billing.claimReconciliation(order.id())).thenReturn(true);
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
        when(billing.claimReconciliation(order.id())).thenReturn(true);
        when(wechat.query(order.id())).thenThrow(new IllegalStateException("network"));
        assertThatThrownBy(()->checkout.refresh(order.id())).hasMessage("network");
        assertThatThrownBy(()->checkout.cancel(order.id())).hasMessage("network");
        verify(billing,never()).cancelOrder(anyString());
        verify(wechat,never()).create(any(),anyLong());
    }
    @Test void aConcurrentRefreshReadsLocalStateWithoutAnotherWechatCall() {
        var order=order("PENDING_PAYMENT");
        when(billing.order(order.id())).thenReturn(order);
        when(billing.claimReconciliation(order.id())).thenReturn(false);
        assertThat(checkout.refresh(order.id())).isSameAs(order);
        verifyNoInteractions(wechat);
    }
    @Test void alipayOrderNotOpenedYetStaysPendingWithoutClosing() {
        var order=order("PENDING_PAYMENT","ALIPAY");
        when(billing.order(order.id())).thenReturn(order);
        when(billing.claimReconciliation(order.id())).thenReturn(true);
        when(alipay.query(order.id())).thenReturn(missingTrade());
        assertThat(checkout.refresh(order.id())).isSameAs(order);
        verify(billing,never()).cancelOrder(anyString());
        verify(alipay,never()).close(anyString());
        verifyNoInteractions(wechat);
    }
    @Test void expiredAlipayOrderClosesTheRemoteTradeBeforeCancelling() {
        var order=order("EXPIRED","ALIPAY");
        when(billing.order(order.id())).thenReturn(order);
        when(billing.claimReconciliation(order.id())).thenReturn(true);
        when(alipay.query(order.id())).thenReturn(json.createObjectNode().put("code","10000").put("trade_status","WAIT_BUYER_PAY"));
        checkout.refresh(order.id());
        var steps=inOrder(alipay,billing);
        steps.verify(alipay).close(order.id());
        steps.verify(billing).cancelOrder(order.id());
    }
    @Test void paidAlipayTradeIsFulfilledInsteadOfCancelled() {
        var order=order("PENDING_PAYMENT","ALIPAY");
        var trade=json.createObjectNode().put("code","10000").put("trade_status","TRADE_SUCCESS");
        when(billing.order(order.id())).thenReturn(order);
        when(alipay.query(order.id())).thenReturn(trade);
        when(billing.confirmAlipay(order.id(),trade)).thenReturn(order);
        checkout.cancel(order.id());
        verify(billing).confirmAlipay(order.id(),trade);
        verify(billing,never()).cancelOrder(anyString());
        verify(alipay,never()).close(anyString());
    }
    @Test void alipayNotificationRunsInTheTenantNamedByTheSignedPassback() {
        String id=UUID.randomUUID().toString();
        var proof=json.createObjectNode().put("passback_params","7_"+id);
        when(billing.confirmAlipay(id,proof)).thenAnswer(invocation->{
            assertThat(TenantContext.require()).isEqualTo(7L);
            return null;
        });
        checkout.notifyAlipay(proof);
        verify(billing).confirmAlipay(id,proof);
        assertThatThrownBy(()->checkout.notifyAlipay(json.createObjectNode().put("passback_params","7:"+id))).hasMessageContaining("无效");
    }
    @Test void cashierUrlIsOnlySignedForAPendingAlipayOrder() {
        TenantContext.set(7L);
        var pending=order("PENDING_PAYMENT","ALIPAY");
        when(billing.order(pending.id())).thenReturn(pending);
        when(alipay.pageUrl(pending,7L)).thenReturn("https://alipay.test/pay");
        assertThat(checkout.alipayUrl(pending.id())).isEqualTo("https://alipay.test/pay");
        var expired=order("EXPIRED","ALIPAY");
        when(billing.order(expired.id())).thenReturn(expired);
        assertThatThrownBy(()->checkout.alipayUrl(expired.id())).hasMessageContaining("不能支付");
        var wechatOrder=order("PENDING_PAYMENT");
        when(billing.order(wechatOrder.id())).thenReturn(wechatOrder);
        assertThatThrownBy(()->checkout.alipayUrl(wechatOrder.id())).hasMessageContaining("不是支付宝订单");
    }
}
