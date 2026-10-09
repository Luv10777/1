package com.wuyao.growth.iam.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 一个网站应用下的微信身份与一方志用户的绑定关系。 */
@Entity
@Table(name = "wechat_accounts")
@Getter
@Setter
public class WechatAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_id", nullable = false, length = 64)
    private String appId;

    @Column(name = "open_id", nullable = false, length = 128)
    private String openId;

    @Column(name = "union_id", length = 128)
    private String unionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 120)
    private String nickname;

    @Column(name = "headimg_url", length = 500)
    private String headimgUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
