package com.wuyao.vimax.dto;

import lombok.Data;

/**
 * 创建视频项目请求
 */
@Data
public class CreateVideoProjectRequest {
    private Long merchantId;
    private String projectName;
    private String userInput;
}
