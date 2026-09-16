package com.wuyao.vimax.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 商家实体
 */
@Entity
@Table(name = "merchants")
@Data
public class Merchant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId = 1L;

    @Column(name = "name", length = 200, nullable = false)
    private String name;

    @Column(name = "type", length = 50)
    private String type;

    @Column(name = "industry", length = 100)
    private String industry;

    @Column(name = "business_hours", length = 200)
    private String businessHours;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    @Column(name = "contact_phone", length = 50)
    private String contactPhone;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "tags", columnDefinition = "TEXT")
    private String tags;

    @Column(name = "status", length = 20)
    private String status = "ACTIVE";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
