package com.wuyao.nexus.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class KnowledgeResponse {
    private Long id;
    private Long tenantId;
    private Long merchantId;
    private Long storeId;
    private String code;
    private String title;
    private String type;
    private String content;
    private String fileUrl;
    private String sourceUrl;
    private String parseStatus;
    private String ocrStatus;
    private String vectorStatus;
    private Integer chunkCount;
    private String structuredData;
    private String metadata;
    private Boolean verified;
    private Long verifiedBy;
    private LocalDateTime verifiedAt;
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
