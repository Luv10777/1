package com.wuyao.vimax.dto;

import lombok.Data;

/**
 * 获取上传 URL 请求
 */
@Data
public class GetUploadUrlRequest {
    private String fileName;
    private String fileType;  // IMAGE, VIDEO, AUDIO, DOCUMENT
    private String mimeType;
    private Long fileSize;
}
