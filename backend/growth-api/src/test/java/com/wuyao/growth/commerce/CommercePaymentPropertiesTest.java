package com.wuyao.growth.commerce;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommercePaymentPropertiesTest {
    private final CommercePaymentProperties payment = new CommercePaymentProperties();
    private final SalesOrder order = new SalesOrder();

    @BeforeEach
    void setUp() {
        payment.setStoreId(5L);
        payment.setRecipient("嘉兴市梧曜科技有限公司");
        payment.setQrCodeUrl("/images/payments/company-alipay-qr.png");
        order.setStoreId(5L);
        order.setTotalAmount(new BigDecimal("200.00"));
    }

    @Test
    void pendingOfficialStoreOrderIncludesManualPaymentWithoutChangingStatusOrAmount() {
        var instructions = payment.forOrder(order);
        var receipt = CommerceDtos.Receipt.of(order, List.of(), false, instructions);

        assertThat(instructions.method()).isEqualTo("ALIPAY_MANUAL");
        assertThat(instructions.recipient()).isEqualTo("嘉兴市梧曜科技有限公司");
        assertThat(instructions.qrCodeUrl()).isEqualTo("/images/payments/company-alipay-qr.png");
        assertThat(receipt.payment()).isEqualTo(instructions);
        assertThat(receipt.status()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(receipt.totalAmount()).isEqualByComparingTo("200.00");
        assertThat(receipt.lookupToken()).isNull();
    }

    @Test
    void otherMerchantsNeverReceiveThePlatformCollectionCode() {
        order.setStoreId(6L);
        assertThat(payment.forOrder(order)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIRMED", "SHIPPED", "COMPLETED", "CANCELLED"})
    void processedOrdersDoNotPromptAnotherPayment(String status) {
        order.setStatus(status);
        assertThat(payment.forOrder(order)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00", "-1.00"})
    void nonPayableOrdersDoNotDisplayTheCode(String amount) {
        order.setTotalAmount(new BigDecimal(amount));
        assertThat(payment.forOrder(order)).isNull();
    }

    @Test
    void missingConfigurationDoesNotOfferPayment() {
        assertThat(new CommercePaymentProperties().forOrder(order)).isNull();
        payment.setQrCodeUrl("");
        assertThat(payment.forOrder(order)).isNull();
        payment.setQrCodeUrl("/images/payments/company-alipay-qr.png");
        payment.setRecipient(" ");
        assertThat(payment.forOrder(order)).isNull();
    }
}
