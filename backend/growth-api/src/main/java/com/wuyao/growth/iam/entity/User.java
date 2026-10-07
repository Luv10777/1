package com.wuyao.growth.iam.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(length = 20)
    private String phone;

    @Column(length = 64)
    private String username;

    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Column(name = "password_failed_attempts", nullable = false)
    private Integer passwordFailedAttempts = 0;

    @Column(name = "password_locked_until")
    private Instant passwordLockedUntil;

    @Column(length = 80)
    private String name;

    /** ACTIVE / DISABLED / REMOVED。只有 ACTIVE 能登录和通过请求校验。 */
    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    /** 商户级角色：OWNER 能进全部门店并管理商户，STAFF 只能进分配给他的门店。 */
    @Column(nullable = false, length = 20)
    private String role = "OWNER";

    @Column(name = "token_version", nullable = false)
    private long tokenVersion = 0;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
