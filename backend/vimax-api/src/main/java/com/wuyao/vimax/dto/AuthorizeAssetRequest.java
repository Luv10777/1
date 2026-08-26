package com.wuyao.vimax.dto;

import lombok.Data;

/**
 * 授权资产请求
 */
@Data
public class AuthorizeAssetRequest {
    private Long merchantId;
    private String authorizationScope;
    private String scopeReference;
    private String expiresAt;  // ISO 8601 格式
}
