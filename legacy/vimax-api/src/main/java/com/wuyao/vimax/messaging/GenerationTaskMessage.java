package com.wuyao.vimax.messaging;

import lombok.Data;

import java.io.Serializable;

/**
 * 生成任务消息
 */
@Data
public class GenerationTaskMessage implements Serializable {
    private Long taskId;
    private String taskType;      // TEXT, IMAGE, VIDEO
    private String prompt;
    private String imageUrl;      // 视频生成用
    private String size;          // 图片尺寸
    private String quality;       // 图片质量
    private Integer duration;     // 视频时长
    private Integer retryCount;   // 重试次数
    private String idempotencyKey; // 幂等性Key
}
