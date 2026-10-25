package com.wuyao.growth.commerce;

import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.product.ProductService;
import com.wuyao.growth.billing.AlipayGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderWriteServiceTest {
    @Mock private ProductService products;
    @Mock private SalesOrderRepository orders;
    @Mock private SalesOrderItemRepository items;
    @Mock private AlipayGateway alipay;

    private OrderWriteService service;

    @BeforeEach
    void setUp() {
        service = new OrderWriteService(products, orders, items, new CommercePaymentProperties(), alipay);
    }

    @Test
    void shippingStoresCarrierAndTrackingInformation() {
        SalesOrder order = order("CONFIRMED");
        when(orders.findForUpdate(7L)).thenReturn(Optional.of(order));
        when(items.findAllByOrderIdOrderByIdAsc(7L)).thenReturn(List.of());

        var updated = service.changeStatus(7L,
                new CommerceDtos.StatusRequest("SHIPPED", "顺丰速运", "SF123"), 3L);

        assertThat(updated.status()).isEqualTo("SHIPPED");
        assertThat(updated.shippingCompany()).isEqualTo("顺丰速运");
        assertThat(updated.trackingNumber()).isEqualTo("SF123");
        verify(orders).saveAndFlush(order);
    }

    @Test
    void shippingCannotAdvanceWithoutCarrier() {
        SalesOrder order = order("CONFIRMED");
        when(orders.findForUpdate(7L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.changeStatus(7L,
                new CommerceDtos.StatusRequest("SHIPPED", " ", null), 3L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("承运方");

        verify(orders, never()).saveAndFlush(order);
    }

    @Test
    void cancellationRestoresReservedInventoryExactlyOnce() {
        SalesOrder order = order("PENDING_CONFIRMATION");
        SalesOrderItem item = new SalesOrderItem();
        item.setProductId(11L);
        item.setQuantity(2);
        when(orders.findForUpdate(7L)).thenReturn(Optional.of(order));
        when(items.findAllByOrderIdOrderByIdAsc(7L)).thenReturn(List.of(item));

        var updated = service.changeStatus(7L,
                new CommerceDtos.StatusRequest("CANCELLED", null, null), 3L);

        assertThat(updated.status()).isEqualTo("CANCELLED");
        verify(products).restoreInventory(11L, 2);
        verify(orders).saveAndFlush(order);
    }

    @Test
    void cannotChangeAnOrderBelongingToAnotherStore() {
        SalesOrder order = order("PENDING_CONFIRMATION");
        order.setStoreId(9L);
        when(orders.findForUpdate(7L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.changeStatus(7L,
                new CommerceDtos.StatusRequest("CONFIRMED", null, null), 3L))
                .isInstanceOf(BizException.class);

        verify(orders, never()).saveAndFlush(order);
    }

    @Test
    void automaticPaymentIsIdempotentAndDoesNotRegressFulfilledOrders() {
        SalesOrder order = order("PENDING_PAYMENT");
        order.setPaymentMethod("ALIPAY");
        order.setPaymentState("PENDING");
        order.setOrderNumber("YF123");
        order.setTotalAmount(new BigDecimal("197.50"));
        when(orders.findForUpdate(7L)).thenReturn(Optional.of(order));
        var proof = new ObjectMapper().createObjectNode().put("trade_status", "TRADE_SUCCESS")
                .put("out_trade_no", "YF123").put("total_amount", "197.50").put("trade_no", "trade123");
        org.mockito.Mockito.doCallRealMethod().when(alipay).validatePaid(proof, "YF123", order.getTotalAmount());

        service.confirmAlipay(7L, proof);
        assertThat(order.getPaymentState()).isEqualTo("PAID");
        assertThat(order.getStatus()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(order.getPaidAt()).isNotNull();
        order.setStatus("SHIPPED");
        service.confirmAlipay(7L, proof);
        assertThat(order.getStatus()).isEqualTo("SHIPPED");
        verify(orders).saveAndFlush(order);
        verify(products, never()).restoreInventory(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void unpaidAlipayOrdersCannotBeManuallyConfirmedOrCancelled() {
        SalesOrder order = order("PENDING_PAYMENT");
        order.setPaymentMethod("ALIPAY");
        order.setPaymentState("PENDING");
        when(orders.findForUpdate(7L)).thenReturn(Optional.of(order));
        for (String status : List.of("CONFIRMED", "CANCELLED", "SHIPPED"))
            assertThatThrownBy(() -> service.changeStatus(7L, new CommerceDtos.StatusRequest(status, null, null), 3L))
                    .isInstanceOf(BizException.class);
        verify(orders, never()).saveAndFlush(order);
    }

    @Test
    void remoteClosureRestoresInventoryOnceAndNeverCancelsAPaidOrder() {
        SalesOrder order = order("PENDING_PAYMENT");
        order.setPaymentMethod("ALIPAY");
        order.setPaymentState("PENDING");
        SalesOrderItem item = new SalesOrderItem();
        item.setProductId(11L);
        item.setQuantity(2);
        when(orders.findForUpdate(7L)).thenReturn(Optional.of(order));
        when(items.findAllByOrderIdOrderByIdAsc(7L)).thenReturn(List.of(item));
        service.cancelUnpaidAlipay(7L);
        service.cancelUnpaidAlipay(7L);
        verify(products).restoreInventory(11L, 2);
        assertThat(order.getPaymentState()).isEqualTo("CLOSED");
        order.setStatus("PENDING_CONFIRMATION");
        order.setPaymentState("PAID");
        assertThatThrownBy(() -> service.cancelUnpaidAlipay(7L)).hasMessageContaining("已付款");
    }

    private static SalesOrder order(String status) {
        SalesOrder order = new SalesOrder();
        order.setId(7L);
        order.setStoreId(3L);
        order.setStatus(status);
        order.setCreatedAt(Instant.now());
        return order;
    }
}
