package com.wuyao.vimax.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建商家事实请求 DTO
 */
@Data
public class CreateMerchantFactRequest {

    @NotBlank(message = "事实类型不能为空")
    private String factType;

    @NotBlank(message = "事实键不能为空")
    private String factKey;

    @NotBlank(message = "事实值不能为空")
    private String factValue;

    private String factSource;

    @NotNull(message = "优先级不能为空")
    private Integer priority = 100;

    private String effectiveFrom;
    private String effectiveTo;
}
