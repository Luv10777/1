package com.wuyao.nexus.dto;

import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class AssetResponse {
    private Long id;
    private Long tenantId;
    private Long merchantId;
    private Long storeId;
    private String code;
    private String name;
    private String type;
    private String category;
    private String[] tags;
    private String fileUrl;
    private Long fileSize;
    private String mimeType;
    private Integer width;
    private Integer height;
    private Integer duration;
    private String thumbnailUrl;
    private String source;
    private String copyrightInfo;
    private String licenseFileUrl;
    private String licenseScope;
    private LocalDate licenseValidFrom;
    private LocalDate licenseValidUntil;
    private Integer usageCount;
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
