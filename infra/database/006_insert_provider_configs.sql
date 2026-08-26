-- 插入 FluAPI 图片生成配置
-- 注意：api_key_encrypted 字段应在应用启动时从环境变量注入，或通过管理界面配置
-- 此处使用占位符，生产环境必须替换为真实的加密密钥
INSERT INTO provider_configs (tenant_id, provider_type, config_name, api_endpoint, api_key_encrypted,
                              rate_limit_per_minute, rate_limit_per_day, timeout_seconds, retry_count,
                              is_active, priority, created_by)
VALUES (1, 'FLUAPI', 'FluAPI Image Generation', 'https://api.fluapi.com/v1/image/generation',
        'PLACEHOLDER_FLUAPI_IMAGE_KEY',
        60, 10000, 60, 3, true, 100, 1);

-- 插入 FluAPI 文本生成配置
INSERT INTO provider_configs (tenant_id, provider_type, config_name, api_endpoint, api_key_encrypted,
                              rate_limit_per_minute, rate_limit_per_day, timeout_seconds, retry_count,
                              is_active, priority, created_by)
VALUES (1, 'FLUAPI', 'FluAPI Text Generation', 'https://api.fluapi.com/v1/text/generation',
        'PLACEHOLDER_FLUAPI_TEXT_KEY',
        60, 10000, 60, 3, true, 100, 1);

-- 插入 ToAPIs Seedance 2.0 配置
INSERT INTO provider_configs (tenant_id, provider_type, config_name, api_endpoint, api_key_encrypted,
                              rate_limit_per_minute, rate_limit_per_day, timeout_seconds, retry_count,
                              is_active, priority, created_by)
VALUES (1, 'TOAPIS', 'ToAPIs Seedance 2.0', 'https://api.toapis.com/v2/video/generation',
        'PLACEHOLDER_TOAPIS_SEEDANCE_KEY',
        30, 5000, 90, 3, true, 100, 1);
