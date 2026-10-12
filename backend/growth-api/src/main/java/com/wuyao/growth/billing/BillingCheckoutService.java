package com.wuyao.growth.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Network calls stay outside account/ledger transactions. Local orders survive network timeouts. */
@Service @RequiredArgsConstructor
public class BillingCheckoutService {
    private final BillingService billing;
    private final WechatPayGateway wechat;
    public BillingDtos.OrderView topUp(BillingDtos.TopUp input) { return checkout(billing.topUp(input)); }
    public BillingDtos.OrderView subscribe(BillingDtos.Subscribe input) {
        if(wechat.configured()) for(String id:billing.reconciliationOrders()) refresh(id);
        return checkout(billing.subscribe(input));
    }
    private BillingDtos.OrderView checkout(BillingDtos.OrderView o) {
        if(!"WECHAT".equals(o.paymentMethod())||!"PENDING_PAYMENT".equals(o.state())||o.paymentCodeUrl()!=null) return o;
        String code=wechat.create(o,TenantContext.require());
        return billing.savePaymentCode(o.id(),code);
    }
    public BillingDtos.OrderView refresh(String id) {
        var o=billing.order(id);
        if(!"WECHAT".equals(o.paymentMethod())||"PAID".equals(o.state())||"CANCELLED".equals(o.state())) return o;
        JsonNode proof=wechat.query(id);
        if("SUCCESS".equals(proof.path("trade_state").asText())) return billing.confirmWechat(id,proof);
        if("CLOSED".equals(proof.path("trade_state").asText())) return billing.cancelOrder(id);
        if("ORDER_NOT_EXIST".equals(proof.path("code").asText())) {
            if("EXPIRED".equals(o.state())) return billing.cancelOrder(id);
            String code=wechat.create(o,TenantContext.require());
            return billing.savePaymentCode(id,code);
        }
        if("EXPIRED".equals(o.state())) {
            wechat.close(id);
            return billing.cancelOrder(id);
        }
        return checkout(o);
    }
    public BillingDtos.OrderView cancel(String id) {
        var o=billing.order(id);
        if("PAID".equals(o.state())||"CANCELLED".equals(o.state())) return o;
        if("WECHAT".equals(o.paymentMethod())) {
            var proof=wechat.query(id);
            if("SUCCESS".equals(proof.path("trade_state").asText())) return billing.confirmWechat(id,proof);
            if(!"ORDER_NOT_EXIST".equals(proof.path("code").asText())) wechat.close(id);
        }
        return billing.cancelOrder(id);
    }
    public void notifyPaid(JsonNode proof) {
        String attach=proof.path("attach").asText();
        if(!attach.matches("[1-9][0-9]*:[0-9a-f-]{36}")) throw BizException.of(ErrorCode.BAD_REQUEST,"支付订单标识无效");
        String[] parts=attach.split(":",2);
        TenantContext.runAs(Long.parseLong(parts[0]),()->billing.confirmWechat(parts[1],proof));
    }
}
