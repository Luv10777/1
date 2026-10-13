package com.wuyao.growth.billing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;

@Component @ConfigurationProperties(prefix="growth.billing") @Getter @Setter
public class BillingProperties {
    private List<Long> operatorUserIds = List.of();
    private long recoveryIntervalMs = 60000;
    private String paymentMethod = "WECHAT";
    private String paymentRecipient = "";
    private String paymentAccount = "";
    private String paymentBank = "";
    private String paymentInstructions = "";
    private String paymentQrCodeUrl = "";
    public BillingDtos.PaymentInstructions payment() {
        boolean available = !operatorUserIds.isEmpty() && !paymentRecipient.isBlank()
            && (!paymentAccount.isBlank() || !paymentQrCodeUrl.isBlank());
        return new BillingDtos.PaymentInstructions(available,paymentRecipient,paymentAccount,paymentBank,
            paymentInstructions,paymentQrCodeUrl);
    }
}
