-- 插入 FluAPI 图片生成配置
-- 注意：api_key_encrypted 字段应在应用启动时从环境变量注入，或通过管理界面配置
-- 此处使用占位符，生产环境必须替换为真实的加密密钥
-- 表名修正为 ai_providers (与 003_ai_gateway_tasks.sql 中定义的表名一致)
INSERT INTO ai_providers (code, name, type, base_url, api_key_encrypted,
                          rate_limit_per_minute, rate_limit_per_day, timeout_seconds, retry_times,
                          status, created_at, updated_at)
VALUES ('FLUAPI_IMAGE', 'FluAPI Image Generation', 'IMAGE', 'https://api.fluapi.com/v1/image/generation',
        'PLACEHOLDER_FLUAPI_IMAGE_KEY',
        60, 10000, 60, 3, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 插入 FluAPI 文本生成配置
INSERT INTO ai_providers (code, name, type, base_url, api_key_encrypted,
                          rate_limit_per_minute, rate_limit_per_day, timeout_seconds, retry_times,
                          status, created_at, updated_at)
VALUES ('FLUAPI_TEXT', 'FluAPI Text Generation', 'TEXT', 'https://api.fluapi.com/v1/text/generation',
        'PLACEHOLDER_FLUAPI_TEXT_KEY',
        60, 10000, 60, 3, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 插入 ToAPIs Seedance 2.0 配置
INSERT INTO ai_providers (code, name, type, base_url, api_key_encrypted,
                          rate_limit_per_minute, rate_limit_per_day, timeout_seconds, retry_times,
                          status, created_at, updated_at)
VALUES ('TOAPIS_SEEDANCE', 'ToAPIs Seedance 2.0', 'VIDEO', 'https://api.toapis.com/v2/video/generation',
        'PLACEHOLDER_TOAPIS_SEEDANCE_KEY',
        30, 5000, 90, 3, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
