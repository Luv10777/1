package com.wuyao.growth.iam.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "sms_codes")
@Getter
@Setter
public class SmsCode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String phone;

    /** 只存哈希。日志和数据库里都不该出现明文验证码。 */
    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(nullable = false, length = 30)
    private String purpose = "LOGIN";

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(nullable = false)
    private Integer attempts = 0;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
