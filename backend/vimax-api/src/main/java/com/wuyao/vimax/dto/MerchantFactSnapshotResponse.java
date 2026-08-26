package com.wuyao.vimax.dto;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 商家事实快照响应 DTO
 */
@Data
public class MerchantFactSnapshotResponse {
    private Long id;
    private Long tenantId;
    private Long merchantId;
    private String snapshotVersion;
    private String snapshotHash;
    private String factsSummary;
    private Boolean isComplete;
    private LocalDateTime createdAt;
}
