package com.wuyao.growth.commerce;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import com.wuyao.growth.common.web.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class CommerceController {
    private final CommerceService service;
    private final CommerceCheckoutService checkout;

    @GetMapping("/api/public/products")
    public ApiResponse<PageResult<CommerceDtos.CatalogItem>> catalog(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        return ApiResponse.ok(service.publicCatalog(page, size, keyword));
    }

    @GetMapping("/api/public/alliance-merchants")
    public ApiResponse<PageResult<CommerceDtos.AllianceMerchant>> allianceMerchants(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(service.publicAllianceMerchants(page, size));
    }

    @PostMapping("/api/public/orders")
    public ApiResponse<CommerceDtos.Receipt> place(@Valid @RequestBody CommerceDtos.PlaceOrderRequest request) {
        return ApiResponse.ok(service.place(request));
    }

    @GetMapping("/api/public/orders/{token}")
    public ApiResponse<CommerceDtos.Receipt> receipt(@PathVariable String token) {
        return ApiResponse.ok(service.publicReceipt(token));
    }

    @PostMapping("/api/public/orders/{token}/alipay")
    public ApiResponse<String> alipay(@PathVariable String token) {
        return ApiResponse.ok(checkout.alipayUrl(token));
    }

    @PostMapping("/api/public/orders/{token}/refresh")
    public ApiResponse<CommerceDtos.Receipt> refresh(@PathVariable String token) {
        return ApiResponse.ok(checkout.refresh(token));
    }

    @GetMapping("/api/stores/{storeId}/orders")
    public ApiResponse<List<CommerceDtos.MerchantOrder>> list(@PathVariable Long storeId,
                                                               @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.merchantOrders(storeId, me.userId()));
    }

    @PatchMapping("/api/stores/{storeId}/orders/{orderId}")
    public ApiResponse<CommerceDtos.MerchantOrder> update(@PathVariable Long storeId, @PathVariable Long orderId,
                                                            @Valid @RequestBody CommerceDtos.StatusRequest request,
                                                            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(checkout.changeStatus(orderId, storeId, me.userId(), request));
    }
}
