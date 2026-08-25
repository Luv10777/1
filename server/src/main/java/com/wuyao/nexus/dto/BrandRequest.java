package com.wuyao.nexus.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BrandRequest {
    @NotBlank(message = "品牌名称不能为空")
    @Size(max = 100, message = "品牌名称不能超过100个字符")
    private String name;

    private String positioning;

    private String targetAudience;

    private String languageStyle;

    @Size(max = 20, message = "主色调不能超过20个字符")
    private String primaryColor;

    private String logoAssets;

    private String platformStyles;
}
