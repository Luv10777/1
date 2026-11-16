package com.wuyao.growth.creative.inspiration;

import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.common.gateway.*;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.Task;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImageInspirationGenerateHandlerTest {
    @Test void originalDrawingRequirementsSurvivePlanningAndHistoricalReuse() {
        var service = mock(ImageInspirationService.class);
        var gateway = mock(AiGateway.class);
        var handler = new ImageInspirationGenerateHandler(service, mock(AssetService.class), mock(ObjectStorage.class), gateway);
        var task = new Task(); task.setTenantId(2L); task.setPayload(Map.of("inspirationId", 7L, "sourceBrief", "大厅场景美化，保持布局和招牌文字"));
        var inspiration = new ImageInspiration(); inspiration.setIdea("改善自然光和材质");
        when(service.begin(7L, task)).thenReturn(true);
        when(service.processing(7L)).thenReturn(inspiration);
        when(service.previousPlans(null)).thenReturn(List.of());
        when(gateway.invokeReal(any())).thenAnswer(call -> {
            ProviderRequest request = call.getArgument(0);
            assertThat(request.prompt()).contains("大厅场景美化，保持布局和招牌文字", "改善自然光和材质");
            var plan = Map.of("title", "自然迎宾厅", "prompt", "保留大厅布局与招牌，用柔和自然光提升材质细节");
            return new ProviderResult(true, "TEST", null, Map.of("plans", List.of(plan, plan, plan)), null, null);
        });
        when(service.completed(eq(7L), eq(task), anyList())).thenAnswer(call -> {
            List<Map<String, Object>> plans = call.getArgument(2);
            assertThat(plans).allSatisfy(p -> assertThat(p).containsEntry("sourceBrief", "大厅场景美化，保持布局和招牌文字"));
            return true;
        });
        assertThat(handler.handle(task)).containsEntry("status", "SUCCEEDED");
    }
}
