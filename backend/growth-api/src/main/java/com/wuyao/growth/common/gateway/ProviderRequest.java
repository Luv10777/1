package com.wuyao.growth.common.gateway;

import java.util.Map;

public record ProviderRequest(
        ModelAlias alias,
        Long tenantId,
        String prompt,
        Map<String, Object> options,
        String idempotencyKey
) {
}
