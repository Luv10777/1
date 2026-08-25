package com.wuyao.nexus.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class WorkRequest {
    @NotBlank(message = "作品标题不能为空")
    @Size(max = 200, message = "作品标题不能超过200个字符")
    private String title;

    @NotNull(message = "作品类型不能为空")
    private String type;

    private String coverUrl;

    private String previewUrl;

    private String contentUrl;

    private String contentText;

    private String workflowId;

    private String workflowVersion;

    private String modelAlias;

    private String promptVersion;

    private String generationParams;

    private String qaResult;

    private Long merchantId;

    private Long storeId;
}
