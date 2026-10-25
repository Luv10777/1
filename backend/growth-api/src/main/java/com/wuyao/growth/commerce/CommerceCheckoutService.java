package com.wuyao.growth.commerce;

import com.fasterxml.jackson.databind.JsonNode;
import com.wuyao.growth.billing.AlipayGateway;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Provider calls run outside the inventory/order transactions. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CommerceCheckoutService {
    private final CommerceService commerce;
    private final OrderWriteService writer;
    private final AlipayGateway alipay;

    public String alipayUrl(String token) {
        var order = commerce.paymentOrder(token);
        if (!pending(order) || !order.expiresAt().isAfter(Instant.now()))
            throw BizException.of(ErrorCode.CONFLICT, "订单已不能支付，请刷新订单状态");
        return alipay.pageUrl(order.orderNumber(), order.totalAmount(), "一方志 · 商城商品", order.expiresAt(),
                "commerce_" + order.tenantId() + "_" + order.id());
    }

    public CommerceDtos.Receipt refresh(String token) {
        var order = commerce.paymentOrder(token);
        if (pending(order)) TenantContext.runAs(order.tenantId(), () -> {
            if (writer.claimPaymentQuery(order.id())) reconcile(order);
            return null;
        });
        return commerce.publicReceipt(token);
    }

    public void recover() {
        if (!alipay.configured()) return;
        for (var order : commerce.reconciliationOrders()) {
            try { if (writer.claimPaymentQuery(order.id())) reconcile(order); }
            catch (RuntimeException failure) { log.warn("商城支付核验等待重试: order={}", order.id()); }
        }
    }

    private void reconcile(CommerceDtos.PaymentOrder order) {
        JsonNode proof = alipay.queryTrade(order.orderNumber());
        if (AlipayGateway.paid(proof)) writer.confirmAlipay(order.id(), proof);
        else if ("TRADE_CLOSED".equals(proof.path("trade_status").asText())) writer.cancelUnpaidAlipay(order.id());
        else if (!order.expiresAt().isAfter(Instant.now())) closeUnpaid(order, proof);
    }

    public CommerceDtos.MerchantOrder changeStatus(Long id, Long storeId, Long userId, CommerceDtos.StatusRequest request) {
        var order = commerce.merchantPaymentOrder(id, storeId, userId);
        if ("ALIPAY".equals(order.paymentMethod()) && "CANCELLED".equals(request.status())) {
            if ("CANCELLED".equals(order.status())) return writer.cancelUnpaidAlipay(id);
            if (!pending(order)) throw BizException.of(ErrorCode.CONFLICT, "订单已付款，请联系售后办理退款");
            JsonNode proof = alipay.queryTrade(order.orderNumber());
            if (AlipayGateway.paid(proof)) {
                writer.confirmAlipay(id, proof);
                throw BizException.of(ErrorCode.CONFLICT, "订单已付款，请刷新订单并联系售后办理退款");
            }
            return closeUnpaid(order, proof);
        }
        return commerce.changeStatus(id, storeId, userId, request);
    }

    private CommerceDtos.MerchantOrder closeUnpaid(CommerceDtos.PaymentOrder order, JsonNode proof) {
        if (AlipayGateway.missing(proof)) {
            // A previously issued cashier URL can still create a trade until time_expire.
            if (order.expiresAt().isAfter(Instant.now()))
                throw BizException.of(ErrorCode.CONFLICT, "收银台尚未生成交易，请在付款时限结束后取消订单");
        } else if (!"TRADE_CLOSED".equals(proof.path("trade_status").asText())) {
            try { alipay.closeTrade(order.orderNumber()); }
            catch (BizException failure) {
                JsonNode latest = alipay.queryTrade(order.orderNumber());
                if (AlipayGateway.paid(latest)) {
                    writer.confirmAlipay(order.id(), latest);
                    throw BizException.of(ErrorCode.CONFLICT, "订单已付款，请刷新订单并联系售后办理退款");
                }
                if (!"TRADE_CLOSED".equals(latest.path("trade_status").asText())) throw failure;
            }
        }
        return writer.cancelUnpaidAlipay(order.id());
    }

    public void notifyAlipay(JsonNode proof) {
        String passback = proof.path("passback_params").asText();
        if (!passback.matches("commerce_[1-9][0-9]*_[1-9][0-9]*"))
            throw BizException.of(ErrorCode.BAD_REQUEST, "商城支付订单标识无效");
        String[] parts = passback.split("_");
        TenantContext.runAs(Long.parseLong(parts[1]), () -> {
            writer.confirmAlipay(Long.parseLong(parts[2]), proof);
            return null;
        });
    }

    private static boolean pending(CommerceDtos.PaymentOrder order) {
        return "ALIPAY".equals(order.paymentMethod()) && "PENDING".equals(order.paymentState())
                && "PENDING_PAYMENT".equals(order.status());
    }
}
