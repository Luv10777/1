package com.wuyao.growth.video.analysis;

import com.wuyao.growth.asset.*;
import com.wuyao.growth.billing.BillingDtos;
import com.wuyao.growth.billing.BillingService;
import com.wuyao.growth.common.gateway.*;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.*;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.video.VideoAnalysisUrlImporter;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoAnalysisServiceTest {
    private final VideoAnalysisRepository analyses = mock(VideoAnalysisRepository.class);
    private final AssetRepository assets = mock(AssetRepository.class);
    private final AssetService assetService = mock(AssetService.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final TaskService tasks = mock(TaskService.class);
    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private final AiGateway gateway = mock(AiGateway.class);
    private final BillingService billing = mock(BillingService.class);
    private final VideoAnalysisService service = new VideoAnalysisService(analyses, assets, assetService, storage,
            tasks, taskRepository, gateway, new VideoAnalysisProperties(), new VideoAnalysisUrlImporter(), billing);

    @Test void oversizedUploadIsRejectedBeforeAnyUploadTicketIsIssued() {
        assertThatThrownBy(() -> service.upload(new VideoAnalysisDtos.Upload("a.mp4", "video/mp4", 104857601L), 1L))
                .isInstanceOf(BizException.class).hasMessageContaining("100 MB");
        verifyNoInteractions(assetService, tasks);
    }
    @Test void actualStorageSizeIsCheckedBeforeEnqueuingAnAnalysis() {
        when(gateway.configured(ModelAlias.VISION_ANALYZER)).thenReturn(true);
        var asset = new Asset(); asset.setId(1L); asset.setType("VIDEO"); asset.setStorageKey("video");
        when(assets.findForUpdate(1L)).thenReturn(Optional.of(asset));
        when(analyses.findByRequestKey("key")).thenReturn(Optional.empty());
        when(storage.stat("video")).thenReturn(Optional.of(new ObjectStorage.StoredObject(104857601, "video/mp4")));
        assertThatThrownBy(() -> service.create(new VideoAnalysisDtos.Create(1L, "key", "ai", ""), 1L))
                .isInstanceOf(BizException.class).hasMessageContaining("100 MB");
        verifyNoInteractions(tasks);
    }
    @Test void expiredWorkersCannotPublishResultsOrChangeAnalysisState() {
        var task = new Task(); task.setId(1L); task.setAttempts(1);
        when(taskRepository.findOwnedRunning(1L, 1)).thenReturn(Optional.empty());
        assertThat(service.completed(2L, task, VideoAnalysisOutputTest.valid())).isFalse();
        assertThat(service.begin(2L, task, "ANALYZING", 65)).isFalse();
        verifyNoInteractions(analyses);
    }
    @Test void linkAnalysisWithTheTeamWalletReservesFromTheTeam() {
        when(gateway.configured(ModelAlias.VISION_ANALYZER)).thenReturn(true);
        when(analyses.findByRequestKey("key")).thenReturn(Optional.empty());
        when(billing.reserve(eq("video-analysis:key"), eq("VIDEO_ANALYSIS"), eq((long) BillingService.VIDEO_ANALYSIS_CREDITS), anyString(),
                eq(BillingDtos.GenerationWallet.TEAM))).thenReturn("team-r1");
        when(assets.saveAndFlush(any())).thenAnswer(call -> { Asset a = call.getArgument(0); a.setId(1L); return a; });
        when(analyses.saveAndFlush(any())).thenAnswer(call -> { VideoAnalysis a = call.getArgument(0); a.setId(2L); return a; });
        var task = new Task(); task.setId(3L);
        when(tasks.submit(anyString(), anyString(), anyMap(), anyString(), anyLong())).thenReturn(task);
        com.wuyao.growth.common.tenant.TenantContext.runAs(3L, () -> service.importUrl(
                new VideoAnalysisDtos.Import("https://example.com/a.mp4", "key", "ai", ""), 1L, BillingDtos.GenerationWallet.TEAM));
        verify(billing, never()).reserve(anyString(), anyString(), anyLong(), anyString());
    }
    @Test void mismatchedIdempotencyKeysCannotCreateASecondPaidTask() {
        when(gateway.configured(ModelAlias.VISION_ANALYZER)).thenReturn(true);
        var asset = new Asset(); asset.setId(1L);
        when(assets.findForUpdate(1L)).thenReturn(Optional.of(asset));
        var prior = new VideoAnalysis(); prior.setAssetId(1L); prior.setMode("real"); prior.setReverseNeed("");
        when(analyses.findByRequestKey("key")).thenReturn(Optional.of(prior));
        assertThatThrownBy(() -> service.create(new VideoAnalysisDtos.Create(1L, "key", "ai", ""), 1L))
                .isInstanceOf(BizException.class).hasMessageContaining("不同的分析");
        verifyNoInteractions(tasks, storage);
    }
}
