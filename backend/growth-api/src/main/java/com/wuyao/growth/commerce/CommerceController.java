package com.wuyao.growth.commerce;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class CommerceController {
    private final CommerceService service;

    @PostMapping("/api/public/orders")
    public ApiResponse<CommerceDtos.Receipt> place(@Valid @RequestBody CommerceDtos.PlaceOrderRequest request) {
        return ApiResponse.ok(service.place(request));
    }

    @GetMapping("/api/public/orders/{token}")
    public ApiResponse<CommerceDtos.Receipt> receipt(@PathVariable String token) {
        return ApiResponse.ok(service.publicReceipt(token));
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
        return ApiResponse.ok(service.changeStatus(orderId, storeId, me.userId(), request));
    }
}
