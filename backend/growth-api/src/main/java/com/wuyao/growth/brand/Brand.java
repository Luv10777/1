package com.wuyao.growth.brand;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 品牌档案。属于商户，门店通过 stores.brand_id 归到某个品牌名下。 */
@Entity
@Table(name = "brands")
@Getter
@Setter
public class Brand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 80)
    private String name;

    /** 新建门店未指定品牌时归到默认品牌。每个商户至多一个，由部分唯一索引保证。 */
    @Column(name = "is_default", nullable = false)
    private boolean defaultBrand;

    /** ACTIVE / ARCHIVED */
    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(length = 60)
    private String industry;

    @Column(length = 120)
    private String slogan;

    @Column(length = 1000)
    private String intro;

    @Column(length = 300)
    private String website;

    @Column(length = 500)
    private String positioning;

    @Column(name = "target_audience", length = 500)
    private String targetAudience;

    @Column(name = "language_style", length = 500)
    private String languageStyle;

    @Column(name = "primary_color", length = 7)
    private String primaryColor;

    @Column(name = "vi_guidelines", length = 2000)
    private String viGuidelines;

    @Column(name = "logo_asset_id")
    private Long logoAssetId;

    @Column(name = "mini_program_qr_asset_id")
    private Long miniProgramQrAssetId;

    @Column(name = "wechat_qr_asset_id")
    private Long wechatQrAssetId;

    @Column(length = 2000)
    private String history;

    @Column(name = "brand_story", length = 4000)
    private String brandStory;

    @Column(name = "core_team", length = 2000)
    private String coreTeam;

    @Column(length = 2000)
    private String culture;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(nullable = false)
    private Long version = 0L;
}
