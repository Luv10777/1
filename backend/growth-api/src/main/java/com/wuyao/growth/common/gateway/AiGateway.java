package com.wuyao.growth.common.gateway;

import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * AI 能力网关：业务模块调用模型的唯一出口。
 *
 * 现在只做了"按别名选一家能干的"。以下几件事都在这一层加，业务代码不受影响：
 *   - 按成本/延迟/可用性动态选型
 *   - 主家限流时熔断切备用
 *   - 逐次计量写用量流水
 *   - 语义缓存
 *   - 灰度和 A/B
 */
@Slf4j
@Service
public class AiGateway {

    private final Map<ModelAlias, List<ProviderAdapter>> routing = new EnumMap<>(ModelAlias.class);
    private final MeterRegistry metrics;

    public AiGateway(List<ProviderAdapter> adapters) {
        this(adapters, new SimpleMeterRegistry());
    }
    @Autowired
    public AiGateway(List<ProviderAdapter> adapters, MeterRegistry metrics) {
        this.metrics = metrics;
        for (ProviderAdapter adapter : adapters) {
            for (ModelAlias alias : adapter.supports()) {
                routing.computeIfAbsent(alias, k -> new ArrayList<>()).add(adapter);
            }
        }
        log.info("AI 网关就绪，已注册适配器: {}", adapters.stream().map(ProviderAdapter::code).toList());
    }

    public ProviderResult invoke(ProviderRequest request) {
        List<ProviderAdapter> candidates = routing.get(request.alias());
        if (candidates == null || candidates.isEmpty()) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "没有供应商支持能力: " + request.alias());
        }
        // TODO 选型策略：目前取第一个。接入真实供应商后在这里做成本/可用性路由和降级。
        ProviderAdapter adapter = candidates.get(0);
        log.debug("网关路由: alias={} -> provider={}", request.alias(), adapter.code());
        return measured(adapter, request);
    }

    public boolean configured(ModelAlias alias) {
        return routing.getOrDefault(alias, List.of()).stream().anyMatch(a -> !"ECHO".equals(a.code()));
    }

    /** Paid production workflows must never fall through to the development echo adapter. */
    public ProviderResult invokeReal(ProviderRequest request) {
        ProviderAdapter adapter = routing.getOrDefault(request.alias(), List.of()).stream()
                .filter(a -> !"ECHO".equals(a.code())).findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.IMAGE_NOT_CONFIGURED, "图片创作模型尚未配置"));
        return measured(adapter, request);
    }
    private ProviderResult measured(ProviderAdapter adapter, ProviderRequest request) {
        String[] tags = {"alias", request.alias().name(), "provider", adapter.code()};
        metrics.counter("ai.calls", tags).increment();
        long start = System.nanoTime();
        try {
            ProviderResult result = adapter.invoke(request);
            if (!result.succeeded()) metrics.counter("ai.failures", tags).increment();
            if (result.output() != null) {
                Object usage = result.output().getOrDefault("_usage", result.output().get("usage"));
                if (usage instanceof Map<?, ?> map && map.get("total_tokens") instanceof Number n && n.doubleValue() >= 0)
                    metrics.counter("ai.tokens", tags).increment(n.doubleValue());
            }
            // Only count vendor-reported cost. Missing cost is not a zero-priced call.
            if (result.cost() != null && result.cost().signum() >= 0) {
                metrics.counter("ai.reported.cost", tags).increment(result.cost().doubleValue());
                metrics.counter("ai.reported.cost.samples", tags).increment();
            }
            return result;
        } catch (RuntimeException e) {
            metrics.counter("ai.failures", tags).increment();
            throw e;
        } finally {
            metrics.timer("ai.duration", tags).record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }
}
