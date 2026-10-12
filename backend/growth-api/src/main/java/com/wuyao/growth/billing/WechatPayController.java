package com.wuyao.growth.billing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/api/billing/wechat") @RequiredArgsConstructor @Slf4j
public class WechatPayController {
    private final WechatPayGateway wechat;
    private final BillingCheckoutService checkout;
    @PostMapping("/notify")
    public ResponseEntity<Map<String,String>> notifyPaid(@RequestBody String body,
        @RequestHeader("Wechatpay-Serial") String serial,@RequestHeader("Wechatpay-Timestamp") String timestamp,
        @RequestHeader("Wechatpay-Nonce") String nonce,@RequestHeader("Wechatpay-Signature") String signature) {
        try {
            checkout.notifyPaid(wechat.notification(body,serial,timestamp,nonce,signature));
            return ResponseEntity.ok(Map.of("code","SUCCESS","message","成功"));
        } catch(RuntimeException e) {
            log.warn("微信支付通知未入账: {}",e.getClass().getSimpleName());
            return ResponseEntity.status(500).body(Map.of("code","FAIL","message","通知未处理成功"));
        }
    }
}
