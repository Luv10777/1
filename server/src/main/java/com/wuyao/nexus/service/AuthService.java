package com.wuyao.nexus.service;

import com.wuyao.nexus.dto.AuthResponse;
import com.wuyao.nexus.dto.LoginRequest;
import com.wuyao.nexus.dto.SendCodeRequest;
import jakarta.servlet.http.HttpServletRequest;

public interface AuthService {
    /**
     * 发送验证码
     */
    void sendVerificationCode(SendCodeRequest request, HttpServletRequest httpRequest);

    /**
     * 登录（登录即注册）
     */
    AuthResponse login(LoginRequest request, HttpServletRequest httpRequest);

    /**
     * 刷新Token
     */
    AuthResponse refreshToken(String refreshToken, HttpServletRequest httpRequest);

    /**
     * 登出
     */
    void logout(Long userId, String deviceId);
}
