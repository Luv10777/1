package com.wuyao.growth.iam.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 一次成功登录。只增不改，用于统计一段时间内登录过的账号数。 */
@Entity
@Table(name = "login_events")
@Getter
@NoArgsConstructor
public class LoginEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public LoginEvent(Long userId, Long tenantId, Instant createdAt) {
        this.userId = userId;
        this.tenantId = tenantId;
        this.createdAt = createdAt;
    }
}
