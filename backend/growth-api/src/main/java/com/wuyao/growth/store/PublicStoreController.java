package com.wuyao.growth.store;

import com.wuyao.growth.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PublicStoreController {
    private final StoreService service;

    @GetMapping("/api/public/stores/{storeId}")
    public ApiResponse<StoreDtos.Profile> publicProfile(@PathVariable Long storeId) {
        return ApiResponse.ok(service.publicProfile(storeId));
    }
}
