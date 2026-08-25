package com.wuyao.nexus.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class BrandResponse {
    private Long id;
    private Long tenantId;
    private Long merchantId;
    private String code;
    private String name;
    private String positioning;
    private String targetAudience;
    private String languageStyle;
    private String primaryColor;
    private String logoAssets;
    private String platformStyles;
    private Integer version;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
