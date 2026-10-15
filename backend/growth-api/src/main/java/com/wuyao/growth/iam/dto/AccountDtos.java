package com.wuyao.growth.iam.dto;

import com.wuyao.growth.iam.entity.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** 商户内的一个账号，供其他模块展示和判断，不暴露登录凭证相关的字段。 */
public final class AccountDtos {

    private AccountDtos() {
    }

    public record Account(Long id, String name, String phone, String role, String status,
                          Instant lastLoginAt, Instant createdAt) {

        public static Account of(User user) {
            return new Account(user.getId(), user.getName(), user.getPhone(), user.getRole(), user.getStatus(),
                    user.getLastLoginAt(), user.getCreatedAt());
        }

        public boolean owner() {
            return "OWNER".equals(role);
        }
    }

    /** 登录者自己的资料。只说明有没有设过密码，不带任何凭证内容。 */
    public record Profile(Long id, String name, String phone, String username, String role,
                          boolean passwordSet, Instant createdAt) {

        public static Profile of(User user) {
            return new Profile(user.getId(), user.getName(), user.getPhone(), user.getUsername(), user.getRole(),
                    user.getPasswordHash() != null, user.getCreatedAt());
        }
    }

    public record RenameRequest(@NotBlank(message = "姓名不能为空") @Size(max = 80, message = "姓名最多 80 个字") String name) {
    }

    /**
     * 设置或修改密码。已有密码时填当前密码；忘了当前密码或第一次设置时，改用发到本人手机的验证码。
     */
    public record PasswordRequest(
            @Size(max = 64) String currentPassword,
            @Pattern(regexp = "^\\d{6}$", message = "验证码必须是 6 位数字") String code,
            @NotBlank(message = "新密码不能为空")
            @Size(min = 8, max = 64, message = "密码长度需为 8–64 位") String newPassword) {
    }
}
