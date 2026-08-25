package com.wuyao.nexus.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class TaskResponse {
    private Long id;
    private String code;
    private String type;
    private String modelAlias;
    private String status;
    private Integer progress;
    private BigDecimal estimatedCost;
    private BigDecimal actualCost;
    private Integer estimatedDuration;
    private Integer actualDuration;
    private String result;
    private String errorCode;
    private String errorMessage;
    private Integer retryCount;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
