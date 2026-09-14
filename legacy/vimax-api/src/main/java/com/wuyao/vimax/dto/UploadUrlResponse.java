package com.wuyao.vimax.dto;

import lombok.Data;

/**
 * 上传 URL 响应
 */
@Data
public class UploadUrlResponse {
    private String uploadUrl;
    private String assetCode;
    private Long expiresIn;  // 秒
}
