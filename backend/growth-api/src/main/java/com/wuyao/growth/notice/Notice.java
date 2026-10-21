package com.wuyao.growth.notice;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 一条站内通知，发给商户里的一个账号。 */
@Entity
@Table(name = "notices")
@Getter
@Setter
public class Notice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long tenantId;
    private Long userId;
    private String category;
    private String title;
    private String body;
    private String link;
    private String dedupeKey;
    private Instant readAt;
    private Instant createdAt = Instant.now();
}
