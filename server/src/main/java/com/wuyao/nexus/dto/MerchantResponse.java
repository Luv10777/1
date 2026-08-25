package com.wuyao.nexus.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MerchantResponse {
    private Long id;
    private Long tenantId;
    private String code;
    private String name;
    private String industry;
    private String logoUrl;
    private String contactName;
    private String contactPhone;
    private String contactEmail;
    private Integer completeness;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
