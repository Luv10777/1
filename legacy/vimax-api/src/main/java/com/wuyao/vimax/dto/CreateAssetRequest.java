package com.wuyao.vimax.dto;

import lombok.Data;

/**
 * 创建资产请求
 */
@Data
public class CreateAssetRequest {
    private String assetCode;
    private String name;
    private String category;
    private Integer width;
    private Integer height;
    private Integer duration;
    private String source;
}
