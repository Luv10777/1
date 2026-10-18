package com.wuyao.growth.commerce;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "growth.commerce.alipay")
@Getter
@Setter
public class CommercePaymentProperties {
    private Long storeId;
    private String recipient = "";
    private String qrCodeUrl = "";

    CommerceDtos.PaymentInstructions forOrder(SalesOrder order) {
        if (storeId == null || !storeId.equals(order.getStoreId())
                || !"PENDING_CONFIRMATION".equals(order.getStatus())
                || order.getTotalAmount() == null || order.getTotalAmount().signum() <= 0
                || recipient.isBlank() || qrCodeUrl.isBlank()) return null;
        return new CommerceDtos.PaymentInstructions("ALIPAY_MANUAL", recipient, qrCodeUrl);
    }
}
