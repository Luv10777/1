package com.wuyao.growth.commerce;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderWriteService {
    private static final BigDecimal MAX_ORDER_AMOUNT = new BigDecimal("999999999999.99");

    private final ProductService products;
    private final SalesOrderRepository orders;
    private final SalesOrderItemRepository items;
    private final CommercePaymentProperties payment;

    @Transactional
    public CommerceDtos.Receipt place(Long storeId, CommerceDtos.PlaceOrderRequest request,
                                      Map<Long, Integer> requested, Long tenantId) {
        var order = new SalesOrder();
        order.setTenantId(tenantId);
        order.setStoreId(storeId);
        order.setOrderNumber("YF" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss"))
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase());
        order.setLookupToken(UUID.randomUUID().toString());
        order.setBuyerName(request.buyerName().trim());
        order.setBuyerPhone(request.buyerPhone().trim());
        order.setShippingAddress(request.shippingAddress().trim());
        order.setBuyerNote(blankToNull(request.buyerNote()));

        BigDecimal total = BigDecimal.ZERO;
        var snapshots = new java.util.ArrayList<ProductService.SaleProduct>();
        for (var entry : requested.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            snapshots.add(products.reserveForOrder(entry.getKey(), entry.getValue()));
        }
        for (var product : snapshots) {
            if (!storeId.equals(product.storeId())) throw BizException.of(ErrorCode.BAD_REQUEST, "一次下单只能购买同一家店铺的商品");
            int quantity = requested.get(product.id());
            BigDecimal lineTotal = product.price().multiply(BigDecimal.valueOf(quantity));
            if (lineTotal.compareTo(MAX_ORDER_AMOUNT) > 0) {
                throw BizException.of(ErrorCode.BAD_REQUEST, "单笔订单金额超出当前系统支持范围");
            }
            total = total.add(lineTotal);
        }
        if (total.compareTo(MAX_ORDER_AMOUNT) > 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "单笔订单金额超出当前系统支持范围");
        }
        order.setTotalAmount(total);
        order.setStatus("PENDING_CONFIRMATION");
        order.setUpdatedAt(Instant.now());
        SalesOrder saved = orders.saveAndFlush(order);

        for (var product : snapshots) {
            int quantity = requested.get(product.id());
            var item = new SalesOrderItem();
            item.setTenantId(tenantId);
            item.setOrderId(saved.getId());
            item.setProductId(product.id());
            item.setProductName(product.name());
            item.setUnitPrice(product.price());
            item.setQuantity(quantity);
            item.setLineTotal(product.price().multiply(BigDecimal.valueOf(quantity)));
            items.save(item);
        }
        return CommerceDtos.Receipt.of(saved, items.findAllByOrderIdOrderByIdAsc(saved.getId()), true,
                payment.forOrder(saved));
    }

    @Transactional
    public CommerceDtos.MerchantOrder changeStatus(Long id, CommerceDtos.StatusRequest request, Long storeId) {
        SalesOrder order = orders.findForUpdate(id)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "订单不存在"));
        if (!storeId.equals(order.getStoreId())) throw BizException.of(ErrorCode.NOT_FOUND, "订单不存在");
        String current = order.getStatus();
        String next = request.status();
        boolean allowed = switch (current) {
            case "PENDING_CONFIRMATION" -> List.of("CONFIRMED", "CANCELLED").contains(next);
            case "CONFIRMED" -> List.of("SHIPPED", "CANCELLED").contains(next);
            case "SHIPPED" -> "COMPLETED".equals(next);
            default -> false;
        };
        if (!allowed) throw BizException.of(ErrorCode.CONFLICT, "当前订单状态不允许此操作");
        if ("SHIPPED".equals(next)) {
            if (request.shippingCompany() == null || request.shippingCompany().isBlank()) {
                throw BizException.of(ErrorCode.BAD_REQUEST, "标记发货前请填写承运方或配送方式");
            }
            order.setShippingCompany(request.shippingCompany().trim());
            order.setTrackingNumber(blankToNull(request.trackingNumber()));
        }
        if ("CANCELLED".equals(next)) {
            items.findAllByOrderIdOrderByIdAsc(id).stream().filter(item -> item.getProductId() != null)
                    .forEach(item -> products.restoreInventory(item.getProductId(), item.getQuantity()));
        }
        order.setStatus(next);
        order.setUpdatedAt(Instant.now());
        orders.saveAndFlush(order);
        return CommerceDtos.MerchantOrder.of(order, items.findAllByOrderIdOrderByIdAsc(id), payment.forOrder(order));
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
