package com.wuyao.growth.copywriting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.gateway.ChatCompletionTextAdapter;
import com.wuyao.growth.common.gateway.ModelAlias;
import com.wuyao.growth.common.gateway.ProviderAdapter;
import com.wuyao.growth.common.gateway.ProviderRequest;
import com.wuyao.growth.common.gateway.ProviderResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 文案创作单独配的模型（COPY_WRITER_*）。文案要看图、能多等一会儿，直播话术和弹幕回复却要快，
 * 所以不和平台文本模型共用一套配置。请求格式与 TEXT_WRITER 相同，直接复用它的适配器。
 * 没配时不登记能力，文案退回平台文本模型。
 */
@Component
public class CopyWriterAdapter implements ProviderAdapter {
    private final ChatCompletionTextAdapter delegate;

    public CopyWriterAdapter(ObjectMapper json,
                             @Value("${growth.copy.writer.url:}") String url,
                             @Value("${growth.copy.writer.api-key:}") String apiKey,
                             @Value("${growth.copy.writer.model:}") String model,
                             @Value("${growth.copy.writer.timeout-seconds:120}") int timeoutSeconds,
                             @Value("${growth.copy.writer.extra-body:}") String extraBody) {
        this.delegate = new ChatCompletionTextAdapter(json, url, apiKey, model, 0.8, 4000, timeoutSeconds, extraBody, "");
    }

    @Override public String code() { return "COPY_TEXT_HTTP"; }

    @Override public Set<ModelAlias> supports() {
        return delegate.supports().isEmpty() ? Set.of() : Set.of(ModelAlias.COPY_WRITER);
    }

    @Override public ProviderResult invoke(ProviderRequest request) { return delegate.invoke(request); }
}
