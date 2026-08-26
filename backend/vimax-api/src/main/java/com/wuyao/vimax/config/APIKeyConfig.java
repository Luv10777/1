package com.wuyao.vimax.config;

import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * API Key 配置
 *
 * 存储各供应商的 API Key
 */
@Configuration
public class APIKeyConfig {

    private static final Map<String, String> API_KEYS = new HashMap<>();

    static {
        // FluAPI 图片生成
        API_KEYS.put("FLUAPI_IMAGE", "sk-1HRjy1gDrU9wF3XoKvKTx9uaEsbniwy1gLbIRnJvp11UFwH4");

        // FluAPI 文本生成
        API_KEYS.put("FLUAPI_TEXT", "sk-S0XMVxl441sA70FuHJxUdckCKIxAEZzKtl4ZDW1mmqJbNMlJ");

        // ToAPIs Seedance 2.0
        API_KEYS.put("TOAPIS_SEEDANCE", "sk-BxRSKVjxvH18Bm8aoLqcqBMBTnhYyvWivEDZxuzIR1LnVv4B");
    }

    public static String getApiKey(String provider, String type) {
        String key = provider.toUpperCase() + "_" + type.toUpperCase();
        return API_KEYS.getOrDefault(key, "");
    }

    public static String getFluApiImageKey() {
        return API_KEYS.get("FLUAPI_IMAGE");
    }

    public static String getFluApiTextKey() {
        return API_KEYS.get("FLUAPI_TEXT");
    }

    public static String getToApisKey() {
        return API_KEYS.get("TOAPIS_SEEDANCE");
    }
}
