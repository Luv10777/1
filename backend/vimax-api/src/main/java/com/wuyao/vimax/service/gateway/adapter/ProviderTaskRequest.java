package com.wuyao.vimax.service.gateway.adapter;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Provider 任务提交请求
 */
@Data
@Builder
public class ProviderTaskRequest {
    private String taskType;           // TEXT, IMAGE, VIDEO
    private String prompt;             // 提示词
    private String imageUrl;           // 参考图URL（图生视频）
    private String modelCapability;    // 模型能力标识

    // 图片生成参数
    private String size;               // 1024x1024, 1024x1792, 1792x1024
    private String quality;            // standard, hd
    private String style;              // vivid, natural

    // 视频生成参数
    private Integer duration;          // 3-10秒
    private String aspectRatio;        // 16:9, 9:16, 1:1
}
