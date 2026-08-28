package com.wuyao.vimax.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * API Key 配置
 *
 * 从环境变量读取各供应商的 API Key
 *
 * 环境变量：
 * - FLUAPI_IMAGE_KEY
 * - FLUAPI_TEXT_KEY
 * - TOAPIS_SEEDANCE_KEY
 */
@Configuration
public class APIKeyConfig {

    @Value("${provider.fluapi.image.key:}")
    private String fluApiImageKey;

    @Value("${provider.fluapi.text.key:}")
    private String fluApiTextKey;

    @Value("${provider.toapis.seedance.key:}")
    private String toApisSeedanceKey;

    public String getApiKey(String provider, String type) {
        String key = provider.toUpperCase() + "_" + type.toUpperCase();

        switch (key) {
            case "FLUAPI_IMAGE":
                return fluApiImageKey;
            case "FLUAPI_TEXT":
                return fluApiTextKey;
            case "TOAPIS_SEEDANCE":
                return toApisSeedanceKey;
            default:
                return "";
        }
    }

    public String getFluApiImageKey() {
        return fluApiImageKey;
    }

    public String getFluApiTextKey() {
        return fluApiTextKey;
    }

    public String getToApisKey() {
        return toApisSeedanceKey;
    }
}
