package com.wuyao.growth.iam.dto;

import com.wuyao.growth.iam.entity.User;

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
}
