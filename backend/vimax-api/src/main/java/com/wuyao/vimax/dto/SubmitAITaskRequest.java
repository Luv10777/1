package com.wuyao.vimax.dto;

import lombok.Data;

/**
 * 提交 AI 任务请求
 */
@Data
public class SubmitAITaskRequest {
    private Long generationTaskId;
    private String taskType;  // TEXT, IMAGE, VIDEO
    private String modelCapability;
    private String prompt;
    private String imageUrl;  // 仅视频生成需要
    private Integer estimatedTokens;  // 文本生成预估
    private Integer imageCount;  // 图片生成数量
    private Integer videoDuration;  // 视频时长（秒）
}
