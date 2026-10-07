package com.wuyao.growth.store;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stores")
@RequiredArgsConstructor
public class StoreController {
    private final StoreService service;

    @GetMapping
    public ApiResponse<List<StoreDtos.StoreView>> list(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.list(me.userId()));
    }

    @PostMapping
    public ApiResponse<StoreDtos.StoreView> create(@Valid @RequestBody StoreDtos.CreateRequest request,
                                                    @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.create(request, me.userId()));
    }

    @GetMapping("/{storeId}")
    public ApiResponse<StoreDtos.StoreView> get(@PathVariable Long storeId,
                                                @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.get(storeId, me.userId()));
    }

    @PutMapping("/{storeId}")
    public ApiResponse<StoreDtos.StoreView> update(@PathVariable Long storeId,
                                                   @Valid @RequestBody StoreDtos.UpdateRequest request,
                                                   @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.update(storeId, request, me.userId()));
    }

    @DeleteMapping("/{storeId}")
    public ApiResponse<Void> archive(@PathVariable Long storeId,
                                     @AuthenticationPrincipal AuthPrincipal me) {
        service.archive(storeId, me.userId());
        return ApiResponse.ok();
    }
}
