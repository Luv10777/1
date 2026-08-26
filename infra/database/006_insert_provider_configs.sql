-- 插入 FluAPI 图片生成配置
INSERT INTO provider_configs (tenant_id, provider_type, config_name, api_endpoint, api_key_encrypted,
                              rate_limit_per_minute, rate_limit_per_day, timeout_seconds, retry_count,
                              is_active, priority, created_by)
VALUES (1, 'FLUAPI', 'FluAPI Image Generation', 'https://api.fluapi.com/v1/image/generation',
        'sk-1HRjy1gDrU9wF3XoKvKTx9uaEsbniwy1gLbIRnJvp11UFwH4',
        60, 10000, 60, 3, true, 100, 1);

-- 插入 FluAPI 文本生成配置
INSERT INTO provider_configs (tenant_id, provider_type, config_name, api_endpoint, api_key_encrypted,
                              rate_limit_per_minute, rate_limit_per_day, timeout_seconds, retry_count,
                              is_active, priority, created_by)
VALUES (1, 'FLUAPI', 'FluAPI Text Generation', 'https://api.fluapi.com/v1/text/generation',
        'sk-S0XMVxl441sA70FuHJxUdckCKIxAEZzKtl4ZDW1mmqJbNMlJ',
        60, 10000, 60, 3, true, 100, 1);

-- 插入 ToAPIs Seedance 2.0 配置
INSERT INTO provider_configs (tenant_id, provider_type, config_name, api_endpoint, api_key_encrypted,
                              rate_limit_per_minute, rate_limit_per_day, timeout_seconds, retry_count,
                              is_active, priority, created_by)
VALUES (1, 'TOAPIS', 'ToAPIs Seedance 2.0', 'https://api.toapis.com/v2/video/generation',
        'sk-BxRSKVjxvH18Bm8aoLqcqBMBTnhYyvWivEDZxuzIR1LnVv4B',
        30, 5000, 90, 3, true, 100, 1);
