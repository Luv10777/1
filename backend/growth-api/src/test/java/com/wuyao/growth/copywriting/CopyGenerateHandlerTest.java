package com.wuyao.growth.copywriting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.common.gateway.*;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.NonRetryableTaskException;
import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.creative.image.ImageCreationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CopyGenerateHandlerTest {
    private final CopyService service = mock(CopyService.class);
    private final CopyContexts contexts = mock(CopyContexts.class);
    private final AiGateway gateway = mock(AiGateway.class);
    private final CopyGenerateHandler handler = new CopyGenerateHandler(service, contexts, mock(AssetService.class),
            mock(ImageCreationService.class), mock(ObjectStorage.class), new CopyModel(gateway, new ObjectMapper()));

    private Task task() {
        var task = new Task(); task.setId(70L); task.setTenantId(3L); task.setAttempts(1); task.setMaxAttempts(3);
        task.setPayload(Map.of("batchId", 7L));
        var batch = new CopyBatch(); batch.setId(7L); batch.setPlatform("MOMENTS");
        when(service.begin(7L, task)).thenReturn(true);
        when(service.processing(7L)).thenReturn(batch);
        when(service.previousAngles(null)).thenReturn(List.of());
        var context = new CopyPrompt.Context(CopyPlatform.MOMENTS, "真诚日常", "NONE", 0, "今天下雨", "商家写的话：今天下雨",
                List.of(), null, List.of(), List.of());
        when(contexts.build(eq(batch), eq(0), any())).thenReturn(new CopyContexts.Built(context, Set.of(), "梧桐小馆"));
        return task;
    }

    /** 文本模型把 JSON 当正文返回，常常还包一层代码块。 */
    private static ProviderResult reply(String... bodies) throws Exception {
        var versions = java.util.Arrays.stream(bodies).map(b -> Map.<String, Object>of("angle", b.substring(0, 2), "body", b)).toList();
        String text = "```json\n" + new ObjectMapper().writeValueAsString(Map.of("versions", versions)) + "\n```";
        return new ProviderResult(true, "TEXT_HTTP", null, Map.of("text", text), null, null);
    }

    @Test void theTextModelIsAskedForJsonWithThePictures() throws Exception {
        var task = task();
        when(gateway.invokeReal(any())).thenReturn(reply("下雨天店里安静", "雨一直下", "路过进来坐坐"));
        when(service.completed(eq(7L), eq(task), any())).thenReturn(true);
        handler.handle(task);
        var request = ArgumentCaptor.forClass(ProviderRequest.class);
        verify(gateway).invokeReal(request.capture());
        assertThat(request.getValue().alias()).isEqualTo(ModelAlias.TEXT_WRITER);
        assertThat(request.getValue().options()).containsEntry("jsonObject", true).containsEntry("system", CopyPrompt.SYSTEM)
                .doesNotContainKey("images");
    }

    @Test void anAnswerThatIsNotJsonFailsTheBatch() {
        var task = task();
        when(gateway.invokeReal(any())).thenReturn(new ProviderResult(true, "TEXT_HTTP", null, Map.of("text", "好的，以下是三条文案"), null, null));
        assertThatThrownBy(() -> handler.handle(task)).isInstanceOf(NonRetryableTaskException.class);
        verify(service).failed(eq(7L), eq(task), contains("按格式"));
    }

    @Test void rejectedVersionsAreReplacedInASecondRound() throws Exception {
        var task = task();
        when(gateway.invokeReal(any())).thenReturn(reply("下雨天店里安静", "今天客流 0 人", "雨一直下"), reply("路过进来坐坐"));
        when(service.completed(eq(7L), eq(task), any())).thenReturn(true);
        assertThat(handler.handle(task)).containsEntry("status", "SUCCEEDED");
        var requests = ArgumentCaptor.forClass(ProviderRequest.class);
        verify(gateway, times(2)).invokeReal(requests.capture());
        assertThat(requests.getAllValues().get(1).prompt()).contains("这次写 1 条").contains("资料里没有的数字 0");
        @SuppressWarnings("unchecked") ArgumentCaptor<List<Map<String, Object>>> saved = ArgumentCaptor.forClass(List.class);
        verify(service).completed(eq(7L), eq(task), saved.capture());
        assertThat(saved.getValue()).extracting(v -> v.get("body")).containsExactly("下雨天店里安静", "雨一直下", "路过进来坐坐");
    }

    @Test void tooFewUsableVersionsFailTheBatchWithoutRetrying() throws Exception {
        var task = task();
        when(gateway.invokeReal(any())).thenReturn(reply("梧桐小馆今天下雨", "今天包邮", "下雨天店里安静"), reply("今天全场包邮"));
        assertThatThrownBy(() -> handler.handle(task)).isInstanceOf(NonRetryableTaskException.class);
        verify(service).failed(eq(7L), eq(task), contains("烟火已退回"));
        verify(service, never()).completed(anyLong(), any(), any());
    }
}
