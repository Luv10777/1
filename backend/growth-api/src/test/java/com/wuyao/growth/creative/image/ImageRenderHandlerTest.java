package com.wuyao.growth.creative.image;

import com.wuyao.growth.common.gateway.AiGateway;
import com.wuyao.growth.common.gateway.ImageModelProperties;
import com.wuyao.growth.common.gateway.ProviderResult;
import com.wuyao.growth.common.metrics.ImageMetrics;
import com.wuyao.growth.common.ratelimit.ImageApiRateLimiter;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.Task;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImageRenderHandlerTest {
    @Test
    void providerUrlCompletesWithoutReadingOrUploadingImage() {
        var service = mock(ImageCreationService.class);
        var gateway = mock(AiGateway.class);
        var storage = mock(ObjectStorage.class);
        var refiner = mock(ImagePromptRefiner.class);
        var limiter = mock(ImageApiRateLimiter.class);
        var metrics = mock(ImageMetrics.class);
        var config = new ImageModelProperties();
        config.getGenerator().setModel("image-model");
        var creation = new ImageCreation();
        creation.setId(5L);
        creation.setTenantId(2L);
        creation.setRequest(new ImageDtos.Create("test-request", "POSTER", "测试海报", List.of(),
            "3:4", "1080P", 1, "微信群", "自然", null));
        var item = new ImageItem();
        item.setId(8L);
        item.setCreationId(5L);
        item.setSpec(new ImageDtos.Spec("海报", "原提示", "", "")
            .withRefinement(new ImageDtos.PromptTrace("生成海报", "SUCCEEDED", "test", "1", Map.of(), 1)));
        var task = new Task();
        task.setId(18L);
        task.setTenantId(2L);
        task.setPayload(Map.of("itemId", 8L));
        String url = "https://images.example/result.png?signature=private";
        var result = new ProviderResult(true, "IMAGE_HTTP", null,
            Map.of("status", "SUCCEEDED", "imageUrl", url), null, null);
        when(service.beginItem(8L, task)).thenReturn(true);
        when(service.itemSnapshot(8L)).thenReturn(item);
        when(service.snapshot(5L)).thenReturn(creation);
        when(service.references(5L)).thenReturn(List.of());
        when(service.reserveProviderSubmission(8L, task)).thenReturn(true);
        when(gateway.invokeReal(any())).thenReturn(result);
        when(storage.stat(anyString())).thenReturn(Optional.empty());

        var handler = new ImageRenderHandler(service, gateway, storage, new ImageRenderer(), config,
            refiner, limiter, metrics);
        assertThat(handler.handle(task)).containsEntry("status", "SUCCEEDED").containsEntry("imageUrl", url);
        verify(service).saveProviderUrl(8L, task, result, url, "t2/generated/8/0/background.png");
        verify(storage, never()).put(anyString(), any(byte[].class), anyString());
        verify(storage, never()).read(anyString(), anyInt());
    }

    @Test
    void showsProviderImageWithoutResizingOrRejectingItsAspectRatio() {
        var service = mock(ImageCreationService.class);
        var gateway = mock(AiGateway.class);
        var storage = mock(ObjectStorage.class);
        var refiner = mock(ImagePromptRefiner.class);
        var limiter = mock(ImageApiRateLimiter.class);
        var metrics = mock(ImageMetrics.class);
        var renderer = new ImageRenderer();
        var config = new ImageModelProperties();
        config.getGenerator().setModel("gpt-image-2.5-flare-ab");

        var request = new ImageDtos.Create("test-request", "POSTER", "测试海报", List.of(),
            "3:4", "1080P", 1, "微信群", "自然", null);
        var creation = new ImageCreation();
        creation.setId(5L);
        creation.setTenantId(2L);
        creation.setRequest(request);
        var item = new ImageItem();
        item.setId(8L);
        item.setCreationId(5L);
        item.setSpec(new ImageDtos.Spec("海报", "原提示", "", "")
            .withRefinement(new ImageDtos.PromptTrace("生成海报", "SUCCEEDED", "test", "1", Map.of(), 1)));
        var task = new Task();
        task.setId(18L);
        task.setTenantId(2L);
        task.setPayload(Map.of("itemId", 8L));

        var returned = new BufferedImage(1374, 1145, BufferedImage.TYPE_INT_RGB);
        returned.setRGB(500, 500, 0xffa12345);
        String encoded = Base64.getEncoder().encodeToString(renderer.png(returned));
        when(service.beginItem(8L, task)).thenReturn(true);
        when(service.itemSnapshot(8L)).thenReturn(item);
        when(service.snapshot(5L)).thenReturn(creation);
        when(service.references(5L)).thenReturn(List.of());
        when(service.reserveProviderSubmission(8L, task)).thenReturn(true);
        when(gateway.invokeReal(any())).thenReturn(new ProviderResult(true, "IMAGE_HTTP", null,
            Map.of("status", "SUCCEEDED", "imageBase64", encoded), null, null));
        when(storage.stat(anyString())).thenReturn(Optional.empty());
        var objects = new ConcurrentHashMap<String, byte[]>();
        doAnswer(invocation -> {
            objects.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(storage).put(anyString(), any(byte[].class), eq("image/png"));
        when(storage.read(anyString(), anyInt())).thenAnswer(invocation -> objects.get(invocation.getArgument(0)));

        var handler = new ImageRenderHandler(service, gateway, storage, renderer, config, refiner, limiter, metrics);
        assertThat(handler.handle(task)).containsEntry("itemId", 8L);
        var raw = objects.get("t2/generated/8/0/background.png");
        var displayed = objects.get("t2/generated/8/0/work.png");
        assertThat(displayed).isEqualTo(raw);
        var image = renderer.decode(displayed);
        assertThat(image.getWidth()).isEqualTo(1374);
        assertThat(image.getHeight()).isEqualTo(1145);
        assertThat(image.getRGB(500, 500)).isEqualTo(0xffa12345);
        verify(service).saveActualDimensions(8L, task, 1374, 1145);
        verify(service).complete(eq(8L), eq(task), eq("t2/generated/8/0/work.png"), any());
    }
}
