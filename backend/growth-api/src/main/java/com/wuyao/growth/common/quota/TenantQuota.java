package com.wuyao.growth.common.quota;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "tenant_quotas")
@Getter
@Setter
public class TenantQuota {
    @Id
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "storage_bytes_used", nullable = false)
    private long storageBytesUsed;

    @Column(name = "storage_bytes_limit", nullable = false)
    private long storageBytesLimit;

    @Column(name = "image_count", nullable = false)
    private int imageCount;

    @Column(name = "image_count_limit", nullable = false)
    private int imageCountLimit;

    @Column(name = "concurrent_limit", nullable = false)
    private int concurrentLimit;
}
