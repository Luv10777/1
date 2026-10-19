package com.wuyao.growth.iam.dto;

import com.wuyao.growth.iam.entity.User;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** 商户内的一个账号，供其他模块展示和判断，不暴露登录凭证相关的字段。 */
public final class AccountDtos {

    private AccountDtos() {
    }

    /** @param avatarUrl 本人上传的头像的临时下载地址，没有上传过时为 null */
    public record Account(Long id, String name, String phone, String role, String status,
                          Instant lastLoginAt, Instant createdAt, String avatarUrl) {

        public static Account of(User user, String avatarUrl) {
            return new Account(user.getId(), user.getName(), user.getPhone(), user.getRole(), user.getStatus(),
                    user.getLastLoginAt(), user.getCreatedAt(), avatarUrl);
        }

        public boolean owner() {
            return "OWNER".equals(role);
        }
    }

    /** 登录者自己的资料。只说明有没有设过密码，不带任何凭证内容。 */
    public record Profile(Long id, String name, String phone, String username, String role,
                          boolean passwordSet, Instant createdAt, String avatarUrl) {

        public static Profile of(User user, String avatarUrl) {
            return new Profile(user.getId(), user.getName(), user.getPhone(), user.getUsername(), user.getRole(),
                    user.getPasswordHash() != null, user.getCreatedAt(), avatarUrl);
        }
    }

    public record AvatarUploadRequest(
            @NotBlank @Pattern(regexp = "image/(jpeg|png)", message = "请选择 JPG 或 PNG 格式的图片") String mimeType) {
    }

    public record AvatarUploadTicket(String uploadKey, String uploadUrl, Instant expiresAt) {
    }

    public record AvatarRequest(@NotBlank @Size(max = 300) String uploadKey) {
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

    /**
     * 注销前给本人看的说明。
     *
     * @param wholeTenant  注销后整个商户是否一起关闭：商户里没有别的管理员时，管理员注销就会关闭商户
     * @param otherMembers 商户里会被一起注销的其他成员数，只注销自己时为 0
     * @param byCode       true 用发到本人手机的验证码确认，false（没绑手机号）用当前密码确认
     * @param losses       会一起作废的东西，每条一句话
     * @param blockedReason 现在不能注销的原因；可以注销时为空
     */
    public record ClosurePreview(boolean wholeTenant, int otherMembers, boolean byCode,
                                 List<String> losses, String blockedReason) {
    }

    /** 确认注销。绑了手机号的账号填验证码，没绑的填当前密码；confirmed 是本人勾选的"我已了解后果"。 */
    public record ClosureRequest(
            @Pattern(regexp = "^\\d{6}$", message = "验证码必须是 6 位数字") String code,
            @Size(max = 64) String currentPassword,
            @AssertTrue(message = "请先确认已了解注销的后果") boolean confirmed) {
    }
}
