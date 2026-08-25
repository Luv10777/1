package com.wuyao.nexus.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MerchantRequest {
    @NotBlank(message = "商家名称不能为空")
    @Size(max = 100, message = "商家名称不能超过100个字符")
    private String name;

    @Size(max = 50, message = "行业不能超过50个字符")
    private String industry;

    private String logoUrl;

    @Size(max = 50, message = "联系人姓名不能超过50个字符")
    private String contactName;

    @Size(max = 20, message = "联系电话不能超过20个字符")
    private String contactPhone;

    @Size(max = 100, message = "联系邮箱不能超过100个字符")
    private String contactEmail;
}
