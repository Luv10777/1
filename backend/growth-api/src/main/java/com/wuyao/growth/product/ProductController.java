package com.wuyao.growth.product;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import com.wuyao.growth.common.web.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping("/api/stores/{storeId}/products")
    public ApiResponse<PageResult<ProductDtos.View>> list(
            @PathVariable Long storeId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(productService.list(storeId, keyword, type, category, page, size, me.userId()));
    }

    @PostMapping("/api/stores/{storeId}/products")
    public ApiResponse<ProductDtos.View> create(@PathVariable Long storeId,
                                                @Valid @RequestBody ProductDtos.CreateRequest req,
                                                @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(productService.create(storeId, req, me.userId()));
    }

    @GetMapping("/api/products/{id}")
    public ApiResponse<ProductDtos.View> get(@PathVariable Long id,
                                             @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(productService.get(id, me.userId()));
    }

    @PatchMapping("/api/products/{id}")
    public ApiResponse<ProductDtos.View> update(@PathVariable Long id,
                                                @Valid @RequestBody ProductDtos.UpdateRequest req,
                                                @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(productService.update(id, req, me.userId()));
    }

    @DeleteMapping("/api/products/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @AuthenticationPrincipal AuthPrincipal me) {
        productService.delete(id, me.userId());
        return ApiResponse.ok();
    }

    @PostMapping("/api/products/{productId}/images")
    public ApiResponse<ProductDtos.ProductImageView> addImage(
            @PathVariable Long productId,
            @Valid @RequestBody ProductDtos.ImageRequest req,
            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(productService.addImage(productId, req, me.userId()));
    }

    @PutMapping("/api/products/{productId}/images/order")
    public ApiResponse<Void> reorderImages(
            @PathVariable Long productId,
            @Valid @RequestBody List<ProductDtos.ImageOrderRequest> req,
            @AuthenticationPrincipal AuthPrincipal me) {
        productService.reorderImages(productId, req, me.userId());
        return ApiResponse.ok();
    }

    @DeleteMapping("/api/products/{productId}/images/{imageId}")
    public ApiResponse<Void> deleteImage(@PathVariable Long productId,
                                         @PathVariable Long imageId,
                                         @AuthenticationPrincipal AuthPrincipal me) {
        productService.deleteImage(productId, imageId, me.userId());
        return ApiResponse.ok();
    }

    @GetMapping("/api/products/{productId}/faqs")
    public ApiResponse<List<ProductDtos.FaqListView>> listFaqs(@PathVariable Long productId,
                                                               @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(productService.listFaqs(productId, me.userId()));
    }

    @PostMapping("/api/products/{productId}/faqs")
    public ApiResponse<ProductDtos.FaqListView> createFaq(@PathVariable Long productId,
                                                          @Valid @RequestBody ProductDtos.FaqRequest req,
                                                          @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(productService.createFaq(productId, req, me.userId()));
    }

    @PatchMapping("/api/products/{productId}/faqs/{faqId}")
    public ApiResponse<ProductDtos.FaqListView> updateFaq(@PathVariable Long productId,
                                                          @PathVariable Long faqId,
                                                          @Valid @RequestBody ProductDtos.FaqUpdateRequest req,
                                                          @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(productService.updateFaq(productId, faqId, req, me.userId()));
    }

    @DeleteMapping("/api/products/{productId}/faqs/{faqId}")
    public ApiResponse<Void> deleteFaq(@PathVariable Long productId,
                                       @PathVariable Long faqId,
                                       @AuthenticationPrincipal AuthPrincipal me) {
        productService.deleteFaq(productId, faqId, me.userId());
        return ApiResponse.ok();
    }
}
