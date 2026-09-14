package com.wuyao.vimax.dto;

import lombok.Data;

/**
 * 确认上传请求
 */
@Data
public class ConfirmUploadRequest {
    private String objectKey;
    private String assetType;
    private String assetCategory;
    private String fileName;
}
