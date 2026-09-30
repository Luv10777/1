package com.wuyao.growth.creative.image;

import com.wuyao.growth.common.gateway.*;
import com.wuyao.growth.common.metrics.ImageMetrics;
import com.wuyao.growth.common.ratelimit.ImageApiRateLimiter;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.Task;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImageRenderHandlerTest {
  @Test void providerTransportFailureLeavesOutcomeUnknownWithoutAutomaticRetry() {
    var service=mock(ImageCreationService.class); var gateway=mock(AiGateway.class); var storage=mock(ObjectStorage.class);
    var limiter=mock(ImageApiRateLimiter.class); var metrics=mock(ImageMetrics.class); var config=new ImageModelProperties();
    config.getGenerator().setModel("image-model"); var creation=creation(); var item=item(); var task=task();
    when(service.beginItem(8L,task)).thenReturn(true); when(service.itemSnapshot(8L)).thenReturn(item); when(service.snapshot(5L)).thenReturn(creation);
    when(service.references(5L)).thenReturn(List.of()); when(service.reserveProviderSubmission(8L,task)).thenReturn(true);
    when(gateway.invokeReal(any())).thenThrow(new ProviderOutcomeUnknownException("中转站响应未完整返回，生成结果状态未确认",new java.io.IOException("timeout")));
    var handler=new ImageRenderHandler(service,gateway,storage,new ImageRenderer(),config,limiter,metrics);
    assertThat(handler.handle(task)).containsEntry("status","UPSTREAM_UNKNOWN");
    verify(service).markUpstreamUnknown(eq(8L),eq(task),contains("结果状态未确认"));
  }
  private ImageCreation creation(){var c=new ImageCreation();c.setId(5L);c.setTenantId(2L);c.setRequest(new ImageDtos.Create("test-request","POSTER","测试海报",List.of(),"3:4","1080P",1,"LOCAL","POSTER","微信群","自然",null));return c;}
  private ImageItem item(){var i=new ImageItem();i.setId(8L);i.setCreationId(5L);i.setSpec(new ImageDtos.Spec("海报","生成海报","",""));return i;}
  private Task task(){var t=new Task();t.setId(18L);t.setTenantId(2L);t.setPayload(Map.of("itemId",8L));return t;}
}
