package com.wuyao.growth.billing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/api/billing/alipay") @RequiredArgsConstructor @Slf4j
public class AlipayController {
    private final AlipayGateway alipay;
    private final BillingCheckoutService checkout;
    private final com.wuyao.growth.commerce.CommerceCheckoutService commerce;
    /** Alipay keeps retrying until the body is exactly "success". */
    @PostMapping(value="/notify",produces="text/plain")
    public String notifyPaid(@RequestParam Map<String,String> params) {
        try {
            var proof=alipay.notification(params);
            if(AlipayGateway.paid(proof)) {
                if(proof.path("passback_params").asText().startsWith("commerce_")) commerce.notifyAlipay(proof);
                else checkout.notifyAlipay(proof);
            }
            return "success";
        } catch(RuntimeException e) {
            log.warn("支付宝通知未入账: {}",e.getClass().getSimpleName());
            return "fail";
        }
    }
}
