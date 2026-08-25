package com.wuyao.nexus.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class StoreResponse {
    private Long id;
    private Long tenantId;
    private Long merchantId;
    private String code;
    private String name;
    private String address;
    private String city;
    private String province;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String contactPhone;
    private String businessHours;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
