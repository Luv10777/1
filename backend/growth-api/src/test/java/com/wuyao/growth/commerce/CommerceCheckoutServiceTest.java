package com.wuyao.growth.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.billing.AlipayGateway;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommerceCheckoutServiceTest {
    private final CommerceService commerce = mock(CommerceService.class);
    private final OrderWriteService writer = mock(OrderWriteService.class);
    private final AlipayGateway alipay = mock(AlipayGateway.class);
    private final CommerceCheckoutService checkout = new CommerceCheckoutService(commerce, writer, alipay);
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach void cleanup() { TenantContext.clear(); }

    private CommerceDtos.PaymentOrder order(Instant expires) {
        return new CommerceDtos.PaymentOrder(7L, 2L, 5L, "YF_TEST7", new BigDecimal("197.50"),
                "PENDING_PAYMENT", "ALIPAY", "PENDING", expires);
    }

    @Test void cashierUsesPersistedAmountAndSeparateCallbackRouting() {
        var order = order(Instant.now().plusSeconds(600));
        when(commerce.paymentOrder("lookup")).thenReturn(order);
        checkout.alipayUrl("lookup");
        verify(alipay).pageUrl("YF_TEST7", new BigDecimal("197.50"), "一方志 · 商城商品", order.expiresAt(), "commerce_2_7");
    }

    @Test void expiredCashierCannotBeReopened() {
        when(commerce.paymentOrder("lookup")).thenReturn(order(Instant.now().minusSeconds(1)));
        assertThatThrownBy(() -> checkout.alipayUrl("lookup")).hasMessageContaining("不能支付");
        verifyNoInteractions(alipay);
    }

    @Test void expiryClosesRemoteTradeBeforeRestoringInventory() {
        when(commerce.paymentOrder("lookup")).thenReturn(order(Instant.now().minusSeconds(1)));
        when(writer.claimPaymentQuery(7L)).thenReturn(true);
        when(alipay.queryTrade("YF_TEST7")).thenReturn(json.createObjectNode().put("trade_status", "WAIT_BUYER_PAY"));
        checkout.refresh("lookup");
        var steps = inOrder(alipay, writer);
        steps.verify(writer).claimPaymentQuery(7L);
        steps.verify(alipay).queryTrade("YF_TEST7");
        steps.verify(alipay).closeTrade("YF_TEST7");
        steps.verify(writer).cancelUnpaidAlipay(7L);
        assertThat(TenantContext.get()).isNull();
    }

    @Test void aMissingTradeCannotReleaseInventoryWhileACashierUrlIsValid() {
        when(commerce.merchantPaymentOrder(7L, 5L, 1L)).thenReturn(order(Instant.now().plusSeconds(600)));
        when(alipay.queryTrade("YF_TEST7")).thenReturn(json.createObjectNode().put("sub_code", "ACQ.TRADE_NOT_EXIST"));
        assertThatThrownBy(() -> checkout.changeStatus(7L, 5L, 1L, new CommerceDtos.StatusRequest("CANCELLED", null, null)))
                .hasMessageContaining("付款时限");
        verify(writer, never()).cancelUnpaidAlipay(7L);
    }

    @Test void expiryWithoutARemoteTradeRestoresInventoryWithoutCallingClose() {
        when(commerce.paymentOrder("lookup")).thenReturn(order(Instant.now().minusSeconds(1)));
        when(writer.claimPaymentQuery(7L)).thenReturn(true);
        when(alipay.queryTrade("YF_TEST7")).thenReturn(json.createObjectNode().put("sub_code", "ACQ.TRADE_NOT_EXIST"));
        checkout.refresh("lookup");
        verify(alipay, never()).closeTrade(anyString());
        verify(writer).cancelUnpaidAlipay(7L);
    }

    @Test void paymentThatWinsACancellationRaceIsRecordedAndInventoryStaysReserved() {
        when(commerce.merchantPaymentOrder(7L, 5L, 1L)).thenReturn(order(Instant.now().plusSeconds(600)));
        var waiting = json.createObjectNode().put("trade_status", "WAIT_BUYER_PAY");
        var paid = json.createObjectNode().put("trade_status", "TRADE_SUCCESS");
        when(alipay.queryTrade("YF_TEST7")).thenReturn(waiting, paid);
        doThrow(BizException.of(ErrorCode.PAYMENT_UNAVAILABLE, "关闭失败")).when(alipay).closeTrade("YF_TEST7");
        assertThatThrownBy(() -> checkout.changeStatus(7L, 5L, 1L, new CommerceDtos.StatusRequest("CANCELLED", null, null)))
                .hasMessageContaining("已付款");
        verify(writer).confirmAlipay(7L, paid);
        verify(writer, never()).cancelUnpaidAlipay(7L);
    }

    @Test void networkFailureCannotCancelOrClaimPayment() {
        when(commerce.paymentOrder("lookup")).thenReturn(order(Instant.now().minusSeconds(1)));
        when(writer.claimPaymentQuery(7L)).thenReturn(true);
        when(alipay.queryTrade("YF_TEST7")).thenThrow(BizException.of(ErrorCode.PAYMENT_UNAVAILABLE, "连接失败"));
        assertThatThrownBy(() -> checkout.refresh("lookup")).hasMessageContaining("连接失败");
        verify(writer, never()).cancelUnpaidAlipay(7L);
        verify(writer, never()).confirmAlipay(anyLong(), any());
    }
}
