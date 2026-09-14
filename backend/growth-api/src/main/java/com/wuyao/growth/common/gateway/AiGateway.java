package com.wuyao.growth.common.gateway;

import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import lombok.extern.slf4j.Slf4j;
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

    public AiGateway(List<ProviderAdapter> adapters) {
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
        return adapter.invoke(request);
    }
}
