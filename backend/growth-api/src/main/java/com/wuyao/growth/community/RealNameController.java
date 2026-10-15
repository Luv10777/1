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
@RequestMapping("/api/identity")
@RequiredArgsConstructor
public class RealNameController {
    private final RealNameService service;

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<RealNameDtos.ApplicationView>> current(@AuthenticationPrincipal AuthPrincipal me) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(service.current(me.userId())));
    }

    @PostMapping("/applications")
    public ResponseEntity<ApiResponse<RealNameDtos.ApplicationView>> submit(@AuthenticationPrincipal AuthPrincipal me,
            @Valid @RequestBody RealNameDtos.ApplicationRequest input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(service.submit(me.userId(), input)));
    }
}
