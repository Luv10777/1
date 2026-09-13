package com.wuyao.growth.common.gateway;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * 占位适配器：不发任何网络请求，原样回显。
 *
 * 存在的意义是让整条链路在没有任何 API Key 的情况下就能跑通，
 * 方便本地开发和联调。接真供应商时新增实现类，别改这个。
 *
 * 它明确标记 provider=ECHO，不会被误当成真实结果。
 */
@Component
public class EchoProviderAdapter implements ProviderAdapter {

    @Override
    public String code() {
        return "ECHO";
    }

    @Override
    public Set<ModelAlias> supports() {
        return Set.of(ModelAlias.values());
    }

    @Override
    public ProviderResult invoke(ProviderRequest request) {
        return new ProviderResult(
                true,
                code(),
                null,
                Map.of(
                        "alias", request.alias().name(),
                        "echo", request.prompt() == null ? "" : request.prompt(),
                        "note", "占位适配器，未调用真实模型"
                ),
                BigDecimal.ZERO,
                null);
    }
}
