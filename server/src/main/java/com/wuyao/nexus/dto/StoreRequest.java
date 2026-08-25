package com.wuyao.nexus.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class StoreRequest {
    @NotBlank(message = "门店名称不能为空")
    @Size(max = 100, message = "门店名称不能超过100个字符")
    private String name;

    @Size(max = 200, message = "地址不能超过200个字符")
    private String address;

    @Size(max = 50, message = "城市不能超过50个字符")
    private String city;

    @Size(max = 50, message = "省份不能超过50个字符")
    private String province;

    private BigDecimal latitude;

    private BigDecimal longitude;

    @Size(max = 20, message = "联系电话不能超过20个字符")
    private String contactPhone;

    private String businessHours;
}
