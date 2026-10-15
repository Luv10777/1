package com.wuyao.growth.community;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "platform_management_audit")
@Getter
@Setter
public class PlatformManagementAudit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;
    @Column(name = "actor_user_id", nullable = false)
    private Long actorUserId;
    @Column(name = "target_type", nullable = false, length = 24)
    private String targetType;
    @Column(name = "target_id", nullable = false)
    private Long targetId;
    @Column(nullable = false, length = 40)
    private String action;
    @Column(length = 500)
    private String note;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
