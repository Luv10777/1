package com.wuyao.growth.product;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class ProductDtos {

    private ProductDtos() {
    }

    public record CreateRequest(
            @NotBlank(message = "商品名称不能为空") @Size(max = 200) String name,
            @NotBlank(message = "商品类型不能为空") @Pattern(regexp = "(?i)PHYSICAL|VOUCHER|实物商品|实物|团购卡券|团购券", message = "商品类型只能是实物商品或团购卡券") String type,
            @NotBlank(message = "商品分类不能为空") @Size(max = 40) String category,
            @NotNull(message = "售价不能为空") @DecimalMin(value = "0.00", message = "售价不能小于 0")
            @Digits(integer = 12, fraction = 2, message = "售价最多支持 12 位整数和 2 位小数") BigDecimal price,
            @NotBlank(message = "售卖单位不能为空") @Size(max = 40) String saleUnit,
            @Size(max = 500) String specification,
            @Size(max = 1000) String promotionRule,
            @Size(max = 2000) String coreSellingPoints,
            @Valid List<ImageRequest> images,
            @Valid List<FaqRequest> faqs) {
    }

    public record UpdateRequest(
            @NotBlank(message = "商品名称不能为空") @Size(max = 200) String name,
            @NotBlank(message = "商品类型不能为空") @Pattern(regexp = "(?i)PHYSICAL|VOUCHER|实物商品|实物|团购卡券|团购券", message = "商品类型只能是实物商品或团购卡券") String type,
            @NotBlank(message = "商品分类不能为空") @Size(max = 40) String category,
            @NotNull(message = "售价不能为空") @DecimalMin(value = "0.00", message = "售价不能小于 0")
            @Digits(integer = 12, fraction = 2, message = "售价最多支持 12 位整数和 2 位小数") BigDecimal price,
            @NotBlank(message = "售卖单位不能为空") @Size(max = 40) String saleUnit,
            @Size(max = 500) String specification,
            @Size(max = 1000) String promotionRule,
            @Size(max = 2000) String coreSellingPoints,
            @NotNull(message = "商品版本不能为空") @PositiveOrZero Long version,
            @Valid List<ImageRequest> images,
            @Valid List<FaqRequest> faqs) {
    }

    public record ImageRequest(
            @NotNull(message = "图片素材不能为空") @Positive Long assetId,
            @PositiveOrZero Integer sortOrder) {
    }

    public record ImageOrderRequest(
            @NotNull @Positive Long imageId,
            @NotNull @PositiveOrZero Integer sortOrder) {
    }

    public record FaqRequest(
            @NotBlank(message = "问题不能为空") @Size(max = 1000) String question,
            @NotBlank(message = "回答不能为空") @Size(max = 4000) String answer,
            @PositiveOrZero Integer sortOrder) {
    }

    public record FaqUpdateRequest(
            @NotBlank(message = "问题不能为空") @Size(max = 1000) String question,
            @NotBlank(message = "回答不能为空") @Size(max = 4000) String answer,
            @PositiveOrZero Integer sortOrder,
            @PositiveOrZero Long version) {
    }

    public record View(Long id, Long storeId, String name, String type, String category,
                       BigDecimal price, String saleUnit, String specification,
                       String promotionRule, String coreSellingPoints, String status,
                       Long version, Instant createdAt, Instant updatedAt,
                       List<ImageView> images, List<FaqView> faqs) {

        static View of(Product product, List<ImageView> images, List<FaqView> faqs) {
            return new View(product.getId(), product.getStoreId(), product.getName(), product.getType(),
                    product.getCategory(), product.getPrice(), product.getSaleUnit(), product.getSpecification(),
                    product.getPromotionRule(), product.getCoreSellingPoints(), product.getStatus(),
                    product.getVersion(), product.getCreatedAt(), product.getUpdatedAt(), images, faqs);
        }
    }

    public record ImageView(Long id, Long assetId, Integer sortOrder) {
        static ImageView of(ProductImage image) {
            return new ImageView(image.getId(), image.getAssetId(), image.getSortOrder());
        }
    }

    public record FaqView(Long id, String question, String answer, String status,
                          Integer sortOrder, Long version, Instant createdAt, Instant updatedAt) {
        static FaqView of(ProductFaq faq) {
            return new FaqView(faq.getId(), faq.getQuestion(), faq.getAnswer(), faq.getStatus(),
                    faq.getSortOrder(), faq.getVersion(), faq.getCreatedAt(), faq.getUpdatedAt());
        }
    }

    public record FaqListView(Long id, Long productId, String question, String answer,
                              String status, Integer sortOrder, Long version,
                              Instant createdAt, Instant updatedAt) {
        static FaqListView of(ProductFaq faq) {
            return new FaqListView(faq.getId(), faq.getProductId(), faq.getQuestion(), faq.getAnswer(),
                    faq.getStatus(), faq.getSortOrder(), faq.getVersion(), faq.getCreatedAt(), faq.getUpdatedAt());
        }
    }

    public record ProductImageView(Long id, Long productId, Long assetId, Integer sortOrder) {
        static ProductImageView of(ProductImage image) {
            return new ProductImageView(image.getId(), image.getProductId(), image.getAssetId(), image.getSortOrder());
        }
    }
}
