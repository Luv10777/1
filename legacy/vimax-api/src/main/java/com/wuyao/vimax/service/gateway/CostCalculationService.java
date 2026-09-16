package com.wuyao.vimax.service.gateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 成本计算服务
 *
 * 根据供应商和模型能力计算成本
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CostCalculationService {

    // 成本单价配置（CNY）
    private static final Map<String, BigDecimal> COST_CONFIG = new HashMap<>();

    static {
        // FluAPI 文本生成（每 1000 tokens）
        COST_CONFIG.put("FLUAPI_TEXT_GPT4", new BigDecimal("0.10"));
        COST_CONFIG.put("FLUAPI_TEXT_GPT3.5", new BigDecimal("0.02"));

        // FluAPI 图片生成（每张）
        COST_CONFIG.put("FLUAPI_IMAGE_2.0", new BigDecimal("0.50"));
        COST_CONFIG.put("FLUAPI_IMAGE_1.0", new BigDecimal("0.30"));

        // ToAPIs 视频生成（每秒）
        COST_CONFIG.put("TOAPIS_VIDEO_SEEDANCE_2.5", new BigDecimal("0.80"));
        COST_CONFIG.put("TOAPIS_VIDEO_SEEDANCE_2.0", new BigDecimal("0.50"));
    }

    /**
     * 估算文本生成成本
     */
    public BigDecimal estimateTextGenerationCost(String provider, String modelCapability, int estimatedTokens) {
        String key = provider + "_TEXT_" + modelCapability;
        BigDecimal unitPrice = COST_CONFIG.getOrDefault(key, new BigDecimal("0.02"));

        // 计算成本（按千 tokens）
        BigDecimal cost = unitPrice.multiply(new BigDecimal(estimatedTokens))
                .divide(new BigDecimal(1000), 4, BigDecimal.ROUND_HALF_UP);

        log.debug("文本生成成本估算: provider={}, tokens={}, cost={}", provider, estimatedTokens, cost);
        return cost;
    }

    /**
     * 估算图片生成成本
     */
    public BigDecimal estimateImageGenerationCost(String provider, String modelCapability, int imageCount) {
        String key = provider + "_IMAGE_" + modelCapability;
        BigDecimal unitPrice = COST_CONFIG.getOrDefault(key, new BigDecimal("0.50"));

        BigDecimal cost = unitPrice.multiply(new BigDecimal(imageCount));

        log.debug("图片生成成本估算: provider={}, count={}, cost={}", provider, imageCount, cost);
        return cost;
    }

    /**
     * 估算视频生成成本
     */
    public BigDecimal estimateVideoGenerationCost(String provider, String modelCapability, int durationSeconds) {
        String key = provider + "_VIDEO_" + modelCapability;
        BigDecimal unitPrice = COST_CONFIG.getOrDefault(key, new BigDecimal("0.50"));

        BigDecimal cost = unitPrice.multiply(new BigDecimal(durationSeconds));

        log.debug("视频生成成本估算: provider={}, duration={}s, cost={}", provider, durationSeconds, cost);
        return cost;
    }

    /**
     * 计算实际成本（基于 usage_data）
     */
    public BigDecimal calculateActualCost(String provider, String jobType, String usageData) {
        // TODO: 解析 usageData JSON 并计算实际成本
        // 示例: {"tokens": 1500} 或 {"duration": 5}

        log.debug("计算实际成本: provider={}, jobType={}", provider, jobType);
        return BigDecimal.ZERO;
    }

    /**
     * 获取单价配置
     */
    public BigDecimal getUnitPrice(String key) {
        return COST_CONFIG.getOrDefault(key, BigDecimal.ZERO);
    }
}
