package com.wuyao.growth.community;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/identity/bank-card")
@RequiredArgsConstructor
public class BankCardController {
    private final BankCardService service;

    @GetMapping
    public ResponseEntity<ApiResponse<BankCardDtos.CardView>> current(@AuthenticationPrincipal AuthPrincipal me) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(service.current(me.userId())));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<BankCardDtos.BindResult>> bind(@AuthenticationPrincipal AuthPrincipal me,
            @Valid @RequestBody BankCardDtos.BindRequest input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(service.bind(me.userId(), input)));
    }
}
