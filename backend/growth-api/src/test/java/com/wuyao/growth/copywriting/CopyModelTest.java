package com.wuyao.growth.copywriting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.gateway.AiGateway;
import com.wuyao.growth.common.gateway.ModelAlias;
import com.wuyao.growth.common.gateway.ProviderRequest;
import com.wuyao.growth.common.gateway.ProviderResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CopyModelTest {
    private final AiGateway gateway = mock(AiGateway.class);
    private final CopyModel model = new CopyModel(gateway, new ObjectMapper());

    private ModelAlias askedAlias() {
        when(gateway.invokeReal(any())).thenReturn(new ProviderResult(true, "x", null, Map.of("text", "{}"), null, null));
        model.ask(1L, "system", "prompt", List.of(), 100, "key");
        var request = ArgumentCaptor.forClass(ProviderRequest.class);
        verify(gateway).invokeReal(request.capture());
        return request.getValue().alias();
    }

    @Test void aSeparateCopyModelIsPreferred() {
        when(gateway.configured(ModelAlias.COPY_WRITER)).thenReturn(true);
        assertThat(CopyModel.configured(gateway)).isTrue();
        assertThat(askedAlias()).isEqualTo(ModelAlias.COPY_WRITER);
    }

    @Test void withoutOneThePlatformTextModelIsUsed() {
        when(gateway.configured(ModelAlias.TEXT_WRITER)).thenReturn(true);
        assertThat(CopyModel.configured(gateway)).isTrue();
        assertThat(askedAlias()).isEqualTo(ModelAlias.TEXT_WRITER);
    }

    @Test void withNeitherCopyIsNotOffered() {
        assertThat(CopyModel.configured(gateway)).isFalse();
    }

    @Test void theCopyModelRegistersOnlyWhenFullyConfigured() {
        var json = new ObjectMapper();
        assertThat(new CopyWriterAdapter(json, "https://models.test/v1/chat/completions", "key", "luna", 120, "").supports())
                .containsExactly(ModelAlias.COPY_WRITER);
        assertThat(new CopyWriterAdapter(json, "", "key", "luna", 120, "").supports()).isEmpty();
    }
}
