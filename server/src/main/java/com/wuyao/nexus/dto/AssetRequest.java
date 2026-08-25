package com.wuyao.nexus.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class AssetRequest {
    @NotBlank(message = "素材名称不能为空")
    @Size(max = 200, message = "素材名称不能超过200个字符")
    private String name;

    @NotNull(message = "素材类型不能为空")
    private String type;

    @Size(max = 50, message = "分类不能超过50个字符")
    private String category;

    private String[] tags;

    @NotBlank(message = "文件URL不能为空")
    private String fileUrl;

    private Long fileSize;

    private String mimeType;

    private Integer width;

    private Integer height;

    private Integer duration;

    private String thumbnailUrl;

    private String source;

    private String copyrightInfo;

    private String licenseFileUrl;

    private String licenseScope;

    private LocalDate licenseValidFrom;

    private LocalDate licenseValidUntil;

    private Long merchantId;

    private Long storeId;
}
