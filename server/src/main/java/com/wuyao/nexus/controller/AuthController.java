package com.wuyao.nexus.controller;

import com.wuyao.nexus.dto.*;
import com.wuyao.nexus.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/send-code")
    public ApiResponse<Void> sendCode(@Valid @RequestBody SendCodeRequest request, HttpServletRequest httpRequest) {
        authService.sendVerificationCode(request, httpRequest);
        return ApiResponse.success(null, "验证码已发送");
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        AuthResponse response = authService.login(request, httpRequest);
        return ApiResponse.success(response, "登录成功");
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(@RequestBody RefreshTokenRequest request, HttpServletRequest httpRequest) {
        AuthResponse response = authService.refreshToken(request.getRefreshToken(), httpRequest);
        return ApiResponse.success(response);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestAttribute("userId") Long userId,
                                     @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        authService.logout(userId, deviceId);
        return ApiResponse.success(null, "登出成功");
    }
}
