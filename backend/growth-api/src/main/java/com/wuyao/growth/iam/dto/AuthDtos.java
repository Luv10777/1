package com.wuyao.growth.iam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 认证相关的入参出参。放一个文件里，省得为四个 record 建四个文件。 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record SendCodeRequest(
            @NotBlank(message = "手机号不能为空")
            @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
            String phone) {
    }

    public record LoginRequest(
            @NotBlank(message = "手机号不能为空")
            @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
            String phone,
            @NotBlank(message = "验证码不能为空")
            @Pattern(regexp = "^\\d{6}$", message = "验证码必须是 6 位数字")
            String code) {
    }

    public record SendCodeResult(boolean developmentMode, long retryAfterSeconds, long expiresInSeconds) {
    }

    public record RefreshRequest(@NotBlank(message = "refreshToken 不能为空") String refreshToken) {
    }

    public record PasswordLoginRequest(
            @NotBlank(message = "账号不能为空")
            @Pattern(regexp = "^[^\\s]{2,64}$", message = "账号格式不正确") String account,
            @NotBlank(message = "密码不能为空")
            @Size(min = 8, max = 64, message = "密码长度需为 8–64 位") String password) {
    }

    public record UserInfo(Long userId, Long tenantId, String phone, String name) {
    }

    public record TokenPair(String accessToken, String refreshToken, long expiresIn, UserInfo user) {
    }
}
