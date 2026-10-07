package com.wuyao.growth.product;

import com.wuyao.growth.asset.Asset;
import com.wuyao.growth.asset.AssetRepository;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.common.web.PageResult;
import com.wuyao.growth.store.StoreAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductImageRepository imageRepository;
    private final ProductFaqRepository faqRepository;
    private final AssetRepository assetRepository;
    private final StoreAccessService storeAccessService;

    @Transactional(readOnly = true)
    public PageResult<ProductDtos.View> list(Long storeId, String keyword, String type, String category,
                                             int page, int size, Long userId) {
        checkPaging(page, size);
        storeAccessService.requireAccess(storeId, userId);
        String normalizedType = normalizeTypeOrNull(type);
        String normalizedKeyword = blankToNull(keyword);
        String normalizedCategory = blankToNull(category);
        var result = productRepository.search(storeId, normalizedKeyword, normalizedType,
                normalizedCategory, PageRequest.of(page, size));
        return PageResult.of(result, result.getContent().stream()
                .map(p -> ProductDtos.View.of(p, imageRepository.findByProductIdOrderBySortOrderAscIdAsc(p.getId())
                        .stream().map(ProductDtos.ImageView::of).toList(), List.of()))
                .toList());
    }

    @Transactional
    public ProductDtos.View create(Long storeId, ProductDtos.CreateRequest req, Long userId) {
        storeAccessService.requireAccess(storeId, userId);
        Long tenantId = TenantContext.require();
        Product product = new Product();
        product.setTenantId(tenantId);
        product.setStoreId(storeId);
        product.setCreatedBy(userId);
        apply(product, req.name(), req.type(), req.category(), req.price(), req.saleUnit(),
                req.specification(), req.promotionRule(), req.coreSellingPoints());
        Product saved = productRepository.save(product);
        if (req.images() != null) replaceImages(saved, req.images(), tenantId);
        if (req.faqs() != null) replaceFaqs(saved, req.faqs(), userId, tenantId);
        return detail(saved);
    }

    @Transactional(readOnly = true)
    public ProductDtos.View get(Long id, Long userId) {
        Product product = findProduct(id);
        storeAccessService.requireAccess(product.getStoreId(), userId);
        return detail(product);
    }

    @Transactional
    public ProductDtos.View update(Long id, ProductDtos.UpdateRequest req, Long userId) {
        Product product = findProductForUpdate(id);
        storeAccessService.requireAccess(product.getStoreId(), userId);
        if (req.version() == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请提供商品版本后再保存");
        }
        checkVersion(product.getVersion(), req.version());
        apply(product, req.name(), req.type(), req.category(), req.price(), req.saleUnit(),
                req.specification(), req.promotionRule(), req.coreSellingPoints());
        touchProduct(product);
        if (req.images() != null) replaceImages(product, req.images(), product.getTenantId());
        if (req.faqs() != null) replaceFaqs(product, req.faqs(), userId, product.getTenantId());
        return detail(productRepository.saveAndFlush(product));
    }

    @Transactional
    public void delete(Long id, Long userId) {
        Product product = findProductForUpdate(id);
        storeAccessService.requireAccess(product.getStoreId(), userId);
        product.setStatus("DELETED");
        product.setDeletedAt(Instant.now());
        product.setUpdatedAt(Instant.now());
        productRepository.save(product);
        faqRepository.findByProductIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(id)
                .forEach(faq -> {
                    faq.setDeletedAt(Instant.now());
                    faq.setStatus("DELETED");
                    faq.setUpdatedAt(Instant.now());
                });
    }

    @Transactional
    public ProductDtos.ProductImageView addImage(Long productId, ProductDtos.ImageRequest req, Long userId) {
        Product product = findProductForUpdate(productId);
        storeAccessService.requireAccess(product.getStoreId(), userId);
        ProductImage image = createImage(product, req, product.getTenantId());
        touchProduct(product);
        try {
            return ProductDtos.ProductImageView.of(imageRepository.saveAndFlush(image));
        } catch (DataIntegrityViolationException e) {
            throw BizException.of(ErrorCode.CONFLICT, "该图片已绑定到商品");
        }
    }

    @Transactional
    public void reorderImages(Long productId, List<ProductDtos.ImageOrderRequest> orders, Long userId) {
        Product product = findProductForUpdate(productId);
        storeAccessService.requireAccess(product.getStoreId(), userId);
        if (orders == null || orders.isEmpty()) return;
        orders.forEach(order -> imageRepository.findByIdAndProductId(order.imageId(), productId)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "商品图片不存在"))
                .setSortOrder(order.sortOrder()));
        touchProduct(product);
    }

    @Transactional
    public void deleteImage(Long productId, Long imageId, Long userId) {
        Product product = findProductForUpdate(productId);
        storeAccessService.requireAccess(product.getStoreId(), userId);
        ProductImage image = imageRepository.findByIdAndProductId(imageId, productId)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "商品图片不存在"));
        imageRepository.delete(image);
        touchProduct(product);
    }

    @Transactional(readOnly = true)
    public List<ProductDtos.FaqListView> listFaqs(Long productId, Long userId) {
        Product product = findProduct(productId);
        storeAccessService.requireAccess(product.getStoreId(), userId);
        return faqRepository.findByProductIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(productId)
                .stream().map(ProductDtos.FaqListView::of).toList();
    }

    @Transactional
    public ProductDtos.FaqListView createFaq(Long productId, ProductDtos.FaqRequest req, Long userId) {
        Product product = findProductForUpdate(productId);
        storeAccessService.requireAccess(product.getStoreId(), userId);
        ProductFaq faq = new ProductFaq();
        faq.setTenantId(product.getTenantId());
        faq.setProductId(productId);
        faq.setCreatedBy(userId);
        faq.setQuestion(req.question().trim());
        faq.setAnswer(req.answer().trim());
        faq.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
        touchProduct(product);
        return ProductDtos.FaqListView.of(faqRepository.saveAndFlush(faq));
    }

    @Transactional
    public ProductDtos.FaqListView updateFaq(Long productId, Long faqId, ProductDtos.FaqUpdateRequest req, Long userId) {
        Product product = findProductForUpdate(productId);
        storeAccessService.requireAccess(product.getStoreId(), userId);
        ProductFaq faq = faqRepository.findByIdAndProductIdAndDeletedAtIsNull(faqId, productId)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "商品问答不存在"));
        checkVersion(faq.getVersion(), req.version());
        faq.setQuestion(req.question().trim());
        faq.setAnswer(req.answer().trim());
        if (req.sortOrder() != null) faq.setSortOrder(req.sortOrder());
        faq.setUpdatedAt(Instant.now());
        touchProduct(product);
        return ProductDtos.FaqListView.of(faqRepository.saveAndFlush(faq));
    }

    @Transactional
    public void deleteFaq(Long productId, Long faqId, Long userId) {
        Product product = findProductForUpdate(productId);
        storeAccessService.requireAccess(product.getStoreId(), userId);
        ProductFaq faq = faqRepository.findByIdAndProductIdAndDeletedAtIsNull(faqId, productId)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "商品问答不存在"));
        faq.setDeletedAt(Instant.now());
        faq.setStatus("DELETED");
        faq.setUpdatedAt(Instant.now());
        touchProduct(product);
    }

    private ProductDtos.View detail(Product product) {
        var images = imageRepository.findByProductIdOrderBySortOrderAscIdAsc(product.getId()).stream()
                .map(ProductDtos.ImageView::of).toList();
        var faqs = faqRepository.findByProductIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(product.getId()).stream()
                .map(ProductDtos.FaqView::of).toList();
        return ProductDtos.View.of(product, images, faqs);
    }

    private Product findProduct(Long id) {
        return productRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "商品不存在"));
    }

    private Product findProductForUpdate(Long id) {
        return productRepository.findForUpdate(id)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "商品不存在"));
    }

    // Every child mutation participates in the same product lock/version as a
    // complete product edit, which replaces its image and FAQ collections.
    // Otherwise an open editor can silently erase answers saved by a live session.
    private static void touchProduct(Product product) {
        Instant now = Instant.now();
        product.setUpdatedAt(now.isAfter(product.getUpdatedAt())
                ? now : product.getUpdatedAt().plusNanos(1_000));
    }

    private void replaceImages(Product product, List<ProductDtos.ImageRequest> requests, Long tenantId) {
        if (requests.stream().map(ProductDtos.ImageRequest::assetId).distinct().count() != requests.size()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "商品图片不能重复");
        }
        imageRepository.deleteByProductId(product.getId());
        // Hibernate may insert before queued deletes; remove old unique bindings before reusing assets.
        imageRepository.flush();
        requests.forEach(req -> imageRepository.save(createImage(product, req, tenantId)));
    }

    private ProductImage createImage(Product product, ProductDtos.ImageRequest req, Long tenantId) {
        Asset asset = assetRepository.findById(req.assetId())
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "图片素材不存在"));
        if (!"READY".equalsIgnoreCase(asset.getStatus()) || !"IMAGE".equalsIgnoreCase(asset.getType())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "只能绑定已上传完成的图片素材");
        }
        if (!tenantId.equals(asset.getTenantId())) {
            throw BizException.of(ErrorCode.FORBIDDEN, "没有访问该图片素材的权限");
        }
        ProductImage image = new ProductImage();
        image.setTenantId(tenantId);
        image.setProductId(product.getId());
        image.setAssetId(req.assetId());
        image.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
        return image;
    }

    private void replaceFaqs(Product product, List<ProductDtos.FaqRequest> requests, Long userId, Long tenantId) {
        faqRepository.findByProductIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(product.getId()).forEach(faq -> {
            faq.setDeletedAt(Instant.now());
            faq.setStatus("DELETED");
            faq.setUpdatedAt(Instant.now());
        });
        requests.forEach(req -> {
            ProductFaq faq = new ProductFaq();
            faq.setTenantId(tenantId);
            faq.setProductId(product.getId());
            faq.setCreatedBy(userId);
            faq.setQuestion(req.question().trim());
            faq.setAnswer(req.answer().trim());
            faq.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
            faqRepository.save(faq);
        });
    }

    private static void apply(Product product, String name, String type, String category,
                              java.math.BigDecimal price, String saleUnit, String specification,
                              String promotionRule, String coreSellingPoints) {
        product.setName(name.trim());
        product.setType(normalizeType(type));
        product.setCategory(category.trim());
        product.setPrice(price);
        product.setSaleUnit(saleUnit.trim());
        product.setSpecification(blankToNull(specification));
        product.setPromotionRule(blankToNull(promotionRule));
        product.setCoreSellingPoints(blankToNull(coreSellingPoints));
    }

    private static String normalizeType(String type) {
        String value = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "PHYSICAL", "实物商品", "实物" -> "PHYSICAL";
            case "VOUCHER", "团购卡券", "团购券" -> "VOUCHER";
            default -> throw BizException.of(ErrorCode.BAD_REQUEST, "商品类型只能是实物商品或团购卡券");
        };
    }

    private static String normalizeTypeOrNull(String type) {
        return blankToNull(type) == null ? null : normalizeType(type);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static void checkPaging(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "page 必须非负，size 必须在 1 到 100 之间");
        }
    }

    private static void checkVersion(Long current, Long expected) {
        if (expected != null && !expected.equals(current)) {
            throw BizException.of(ErrorCode.CONFLICT, "商品已被其他操作修改，请刷新后重试");
        }
    }
}
