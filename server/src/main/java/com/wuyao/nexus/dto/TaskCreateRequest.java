package com.wuyao.nexus.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class TaskCreateRequest {
    @NotNull(message = "任务类型不能为空")
    private String type;

    @NotBlank(message = "模型别名不能为空")
    private String modelAlias;

    @NotNull(message = "输入参数不能为空")
    private Map<String, Object> inputParams;

    private String webhookUrl;

    private Integer priority;

    private Long merchantId;

    private Long storeId;
}
