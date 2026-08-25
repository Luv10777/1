package com.wuyao.nexus.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class WorkResponse {
    private Long id;
    private Long tenantId;
    private Long merchantId;
    private Long storeId;
    private String code;
    private String title;
    private String type;
    private Integer version;
    private String coverUrl;
    private String previewUrl;
    private String contentUrl;
    private String contentText;
    private String workflowId;
    private String workflowVersion;
    private String modelAlias;
    private String promptVersion;
    private BigDecimal generationCost;
    private Integer generationDuration;
    private String generationParams;
    private String qaResult;
    private String reviewStatus;
    private String reviewNotes;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private LocalDateTime publishedAt;
    private String platformContentIds;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
