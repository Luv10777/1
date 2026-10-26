package com.wuyao.growth.commerce;

import com.wuyao.growth.product.ProductDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.AssertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class CommerceDtos {
    private CommerceDtos() {}

    public record RequestedItem(@NotNull @Min(1) Long productId, @Min(1) @Max(99) int quantity) {}
    public record PlaceOrderRequest(
            @NotEmpty @Size(max = 20) List<@Valid RequestedItem> items,
            @NotBlank @Size(max = 80) String buyerName,
            @NotBlank @Pattern(regexp = "[0-9+()\\- ]{6,20}") String buyerPhone,
            @NotBlank @Size(max = 500) String shippingAddress,
            @Size(max = 500) String buyerNote) {}

    /** 商户联盟商品墙上的一张卡片。 */
    public record CatalogItem(Long id, Long storeId, String storeName, String name, String category, BigDecimal price,
                              String saleUnit, String specification, Integer inventory, Long coverAssetId) {
        static CatalogItem of(ProductDtos.PublicListing listing, String storeName) {
            return new CatalogItem(listing.id(), listing.storeId(), storeName, listing.name(), listing.category(),
                    listing.price(), listing.saleUnit(), listing.specification(), listing.inventory(), listing.coverAssetId());
        }
    }

    /** 商户联盟「入驻商户」栏的一家门店：在售实物商品总数和最近的几件。没有商品时 products 为空。 */
    public record AllianceStore(Long storeId, String storeName, long productTotal, List<CatalogItem> products) {}

    public record Line(Long productId, String productName, BigDecimal unitPrice, int quantity, BigDecimal lineTotal) {
        static Line of(SalesOrderItem item) {
            return new Line(item.getProductId(), item.getProductName(), item.getUnitPrice(), item.getQuantity(), item.getLineTotal());
        }
    }

    public record PaymentInstructions(String method, String recipient, String qrCodeUrl) {}

    public record PaymentOrder(Long id, Long tenantId, Long storeId, String orderNumber, BigDecimal totalAmount,
                               String status, String paymentMethod, String paymentState, Instant expiresAt) {
        static PaymentOrder of(SalesOrder order) {
            return new PaymentOrder(order.getId(), order.getTenantId(), order.getStoreId(), order.getOrderNumber(),
                    order.getTotalAmount(), order.getStatus(), order.getPaymentMethod(), order.getPaymentState(),
                    order.getPaymentExpiresAt());
        }
    }

    public record Receipt(Long id, String orderNumber, String lookupToken, String status, BigDecimal totalAmount,
                          String shippingCompany, String trackingNumber, List<Line> items, Instant createdAt,
                          PaymentInstructions payment, String paymentMethod, String paymentState, Instant paidAt,
                          Instant paymentExpiresAt) {
        static Receipt of(SalesOrder order, List<SalesOrderItem> items, boolean includeToken,
                          PaymentInstructions payment) {
            return new Receipt(order.getId(), order.getOrderNumber(), includeToken ? order.getLookupToken() : null,
                    order.getStatus(), order.getTotalAmount(), order.getShippingCompany(), order.getTrackingNumber(),
                    items.stream().map(Line::of).toList(), order.getCreatedAt(), payment, order.getPaymentMethod(),
                    order.getPaymentState(), order.getPaidAt(), order.getPaymentExpiresAt());
        }
    }

    public record MerchantOrder(Long id, String orderNumber, String buyerName, String buyerPhone,
                                String shippingAddress, String shippingCompany, String trackingNumber,
                                String buyerNote, String status, BigDecimal totalAmount,
                                List<Line> items, Instant createdAt, Instant updatedAt,
                                PaymentInstructions payment, String paymentMethod, String paymentState, Instant paidAt) {
        static MerchantOrder of(SalesOrder order, List<SalesOrderItem> items, PaymentInstructions payment) {
            return new MerchantOrder(order.getId(), order.getOrderNumber(), order.getBuyerName(), order.getBuyerPhone(),
                    order.getShippingAddress(), order.getShippingCompany(), order.getTrackingNumber(), order.getBuyerNote(),
                    order.getStatus(), order.getTotalAmount(),
                    items.stream().map(Line::of).toList(), order.getCreatedAt(), order.getUpdatedAt(), payment,
                    order.getPaymentMethod(), order.getPaymentState(), order.getPaidAt());
        }
    }

    public record StatusRequest(@NotBlank @Pattern(regexp = "CONFIRMED|SHIPPED|COMPLETED|CANCELLED") String status,
                                @Size(max = 60) String shippingCompany,
                                @Size(max = 120) String trackingNumber) {
        @AssertTrue(message = "标记发货前请填写承运方或配送方式")
        public boolean shippingCompanyRequiredForShipment() {
            return !"SHIPPED".equals(status) || (shippingCompany != null && !shippingCompany.isBlank());
        }
    }
}
