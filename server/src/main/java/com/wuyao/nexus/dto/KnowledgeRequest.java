package com.wuyao.nexus.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class KnowledgeRequest {
    @NotBlank(message = "知识标题不能为空")
    @Size(max = 200, message = "知识标题不能超过200个字符")
    private String title;

    @NotNull(message = "知识类型不能为空")
    private String type;

    private String content;

    private String fileUrl;

    private String sourceUrl;

    private String structuredData;

    private String metadata;

    private Long merchantId;

    private Long storeId;
}
