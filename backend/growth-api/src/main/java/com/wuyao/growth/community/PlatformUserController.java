package com.wuyao.growth.community;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import com.wuyao.growth.common.web.PageResult;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/platform/users")
@RequiredArgsConstructor
public class PlatformUserController {
    private final PlatformUserService service;

    @ModelAttribute
    void preventCaching(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

    @GetMapping
    public ApiResponse<PageResult<PlatformDtos.UserView>> users(@AuthenticationPrincipal AuthPrincipal me,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status, @RequestParam(required = false) String role,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.users(me, page, size, status, role, keyword));
    }

    @GetMapping("/summary")
    public ApiResponse<PlatformDtos.UserSummary> summary(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.summary(me));
    }

    @PostMapping("/{userId}/suspension")
    public ApiResponse<PlatformDtos.UserView> suspension(@AuthenticationPrincipal AuthPrincipal me, @PathVariable Long userId,
            @Valid @RequestBody PlatformDtos.UserSuspensionRequest request) {
        return ApiResponse.ok(service.suspension(me, userId, request));
    }

    @PostMapping("/{userId}/membership")
    public ApiResponse<PlatformDtos.UserView> membership(@AuthenticationPrincipal AuthPrincipal me, @PathVariable Long userId,
            @Valid @RequestBody PlatformDtos.MembershipRequest request) {
        return ApiResponse.ok(service.membership(me, userId, request));
    }

    @PostMapping("/{userId}/credits")
    public ApiResponse<PlatformDtos.UserView> credits(@AuthenticationPrincipal AuthPrincipal me, @PathVariable Long userId,
            @Valid @RequestBody PlatformDtos.CreditGrantRequest request) {
        return ApiResponse.ok(service.credits(me, userId, request));
    }
}
