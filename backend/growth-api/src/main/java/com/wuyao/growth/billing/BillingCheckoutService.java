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
    private final AlipayGateway alipay;
    public BillingDtos.OrderView topUp(BillingDtos.TopUp input) { return checkout(billing.topUp(input)); }
    public BillingDtos.OrderView subscribe(BillingDtos.Subscribe input) {
        if(reconciles()) for(String id:billing.reconciliationOrders()) refresh(id);
        return checkout(billing.subscribe(input));
    }
    /** Pending orders are only created while a provider is configured, so there is nothing to reconcile otherwise. */
    public boolean reconciles() { return wechat.configured()||alipay.configured(); }
    private BillingDtos.OrderView checkout(BillingDtos.OrderView o) {
        if(!"WECHAT".equals(o.paymentMethod())||!"PENDING_PAYMENT".equals(o.state())||o.paymentCodeUrl()!=null) return o;
        String code=wechat.create(o,TenantContext.require());
        return billing.savePaymentCode(o.id(),code);
    }
    public String alipayUrl(String id) {
        var o=billing.order(id);
        if(!"ALIPAY".equals(o.paymentMethod())) throw BizException.of(ErrorCode.BAD_REQUEST,"该订单不是支付宝订单");
        if(!"PENDING_PAYMENT".equals(o.state())) throw BizException.of(ErrorCode.BAD_REQUEST,"订单已不能支付，请刷新后重新下单");
        return alipay.pageUrl(o,TenantContext.require());
    }
    public BillingDtos.OrderView refresh(String id) {
        var o=billing.order(id);
        if(!java.util.Set.of("WECHAT","ALIPAY").contains(o.paymentMethod())||"PAID".equals(o.state())||"CANCELLED".equals(o.state())) return o;
        if(!billing.claimReconciliation(id)) return billing.order(id);
        if("ALIPAY".equals(o.paymentMethod())) return refreshAlipay(o);
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
    private BillingDtos.OrderView refreshAlipay(BillingDtos.OrderView o) {
        JsonNode trade=alipay.query(o.id());
        if(AlipayGateway.paid(trade)) return billing.confirmAlipay(o.id(),trade);
        if("TRADE_CLOSED".equals(trade.path("trade_status").asText())) return billing.cancelOrder(o.id());
        if(!"EXPIRED".equals(o.state())) return o;
        // time_expire stops new payments, so an expired order that never reached the cashier can close locally.
        if(!AlipayGateway.missing(trade)) alipay.close(o.id());
        return billing.cancelOrder(o.id());
    }
    public BillingDtos.OrderView cancel(String id) {
        var o=billing.order(id);
        if("PAID".equals(o.state())||"CANCELLED".equals(o.state())) return o;
        if("WECHAT".equals(o.paymentMethod())) {
            var proof=wechat.query(id);
            if("SUCCESS".equals(proof.path("trade_state").asText())) return billing.confirmWechat(id,proof);
            if(!"ORDER_NOT_EXIST".equals(proof.path("code").asText())) wechat.close(id);
        }
        if("ALIPAY".equals(o.paymentMethod())) {
            var trade=alipay.query(id);
            if(AlipayGateway.paid(trade)) return billing.confirmAlipay(id,trade);
            if(!AlipayGateway.missing(trade)&&!"TRADE_CLOSED".equals(trade.path("trade_status").asText())) alipay.close(id);
        }
        return billing.cancelOrder(id);
    }
    public void notifyPaid(JsonNode proof) {
        String attach=proof.path("attach").asText();
        if(!attach.matches("[1-9][0-9]*:[0-9a-f-]{36}")) throw BizException.of(ErrorCode.BAD_REQUEST,"支付订单标识无效");
        String[] parts=attach.split(":",2);
        TenantContext.runAs(Long.parseLong(parts[0]),()->billing.confirmWechat(parts[1],proof));
    }
    public void notifyAlipay(JsonNode proof) {
        String passback=proof.path("passback_params").asText();
        if(!passback.matches("[1-9][0-9]*_[0-9a-f-]{36}")) throw BizException.of(ErrorCode.BAD_REQUEST,"支付订单标识无效");
        String[] parts=passback.split("_",2);
        TenantContext.runAs(Long.parseLong(parts[0]),()->billing.confirmAlipay(parts[1],proof));
    }
}
