package com.wuyao.growth.creative.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.common.gateway.AiGateway;
import com.wuyao.growth.common.gateway.ImageModelProperties;
import com.wuyao.growth.common.ratelimit.TenantRateLimiter;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.TaskService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ImageCreationServiceDownloadTest {
    @Test
    void downloadedImagesCompleteTheCreationAndReleaseItsPermit() {
        var creations = mock(ImageCreationRepository.class);
        var items = mock(ImageItemRepository.class);
        var limiter = mock(TenantRateLimiter.class);
        var service = new ImageCreationService(creations, items, mock(AssetService.class),
            mock(TaskService.class), mock(ObjectStorage.class), mock(AiGateway.class),
            new ImageModelProperties(), new ObjectMapper(), limiter);

        var creation = new ImageCreation();
        creation.setId(7L);
        creation.setTenantId(2L);
        creation.setStatus("GENERATING");
        creation.setConcurrencyPermitHeld(true);
        var first = new ImageItem();
        first.setId(8L);
        first.setCreationId(7L);
        first.setStatus("GENERATING");
        var second = new ImageItem();
        second.setId(9L);
        second.setCreationId(7L);
        second.setStatus("GENERATING");
        when(creations.findById(7L)).thenReturn(Optional.of(creation));
        when(items.lock(8L)).thenReturn(Optional.of(first));
        when(items.lock(9L)).thenReturn(Optional.of(second));
        when(items.findByCreationIdOrderByOrdinal(7L)).thenReturn(List.of(first, second));
        when(creations.clearConcurrencyPermit(7L)).thenReturn(1, 0);

        service.markImagePersisted(8L, "t2/generated/8/0/background.png", 2048, 2048, null);
        assertThat(first.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(creation.getStatus()).isEqualTo("GENERATING");
        verifyNoInteractions(limiter);

        service.markImagePersisted(9L, "t2/generated/9/0/background.png", 2048, 2048, null);
        assertThat(second.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(creation.getStatus()).isEqualTo("SUCCEEDED");
        verify(limiter).releaseImageGeneration(2L);
    }
}
