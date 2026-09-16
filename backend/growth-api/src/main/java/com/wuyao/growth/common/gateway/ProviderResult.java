package com.wuyao.growth.common.gateway;

import java.math.BigDecimal;
import java.util.Map;

/**
 * @param providerJobId 异步供应商返回的作业号，轮询用
 * @param cost          本次实际花费，直接进用量流水
 */
public record ProviderResult(
        boolean succeeded,
        String providerCode,
        String providerJobId,
        Map<String, Object> output,
        BigDecimal cost,
        String errorMessage
) {
}
