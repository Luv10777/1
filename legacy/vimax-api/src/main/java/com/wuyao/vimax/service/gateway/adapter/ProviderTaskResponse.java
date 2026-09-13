package com.wuyao.vimax.service.gateway.adapter;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Provider 任务响应
 */
@Data
@Builder
public class ProviderTaskResponse {
    private String providerJobId;      // Provider 返回的任务ID
    private String status;             // PENDING, PROCESSING, COMPLETED, FAILED
    private Integer progress;          // 0-100

    // 同步返回（FluAPI图片）
    private String resultUrl;          // 图片/视频URL
    private String thumbnailUrl;       // 缩略图URL

    // 异步返回（ToAPIs视频）
    private Integer estimatedDuration; // 预估完成时间（秒）

    // 完成后的元数据
    private Integer width;
    private Integer height;
    private BigDecimal durationSeconds;
    private Long fileSizeBytes;

    // 错误信息
    private String errorCode;
    private String errorMessage;

    // 时间戳
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
