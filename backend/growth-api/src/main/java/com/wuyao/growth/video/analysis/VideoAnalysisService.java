package com.wuyao.growth.video.analysis;

import com.wuyao.growth.asset.*;
import com.wuyao.growth.common.gateway.*;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.*;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.*;

@Service
@RequiredArgsConstructor
public class VideoAnalysisService {
    private final VideoAnalysisRepository analyses;
    private final AssetRepository assets;
    private final AssetService assetService;
    private final ObjectStorage storage;
    private final TaskService tasks;
    private final TaskRepository taskRepository;
    private final AiGateway gateway;
    private final VideoAnalysisProperties config;
    private final com.wuyao.growth.video.VideoAnalysisUrlImporter urlImporter;

    public VideoAnalysisDtos.Limits limits() {
        return new VideoAnalysisDtos.Limits(config.getMaxBytes(), config.getMaxDurationSeconds(), config.getMaxFrames(),
                gateway.configured(ModelAlias.VISION_ANALYZER), gateway.configured(ModelAlias.AUDIO_ANALYZER));
    }

    public AssetDtos.UploadTicket upload(VideoAnalysisDtos.Upload request, Long userId) {
        checkSize(request.sizeBytes());
        String extension = request.name().toLowerCase(Locale.ROOT);
        if (!extension.endsWith(".mp4") && !extension.endsWith(".mov")) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请上传 MP4 或 MOV 视频");
        }
        return assetService.presignUpload(new AssetDtos.PresignRequest(request.name(), "VIDEO", request.mimeType()), userId);
    }

    @Transactional
    public VideoAnalysisDtos.View create(VideoAnalysisDtos.Create request, Long userId) {
        if (!gateway.configured(ModelAlias.VISION_ANALYZER)) {
            throw BizException.of(ErrorCode.VIDEO_NOT_CONFIGURED, "视频反推模型尚未配置");
        }
        Asset asset = assets.findForUpdate(request.assetId())
                .orElseThrow(() -> BizException.of(ErrorCode.ASSET_NOT_FOUND, "视频素材不存在"));
        String need = request.reverseNeed() == null ? "" : request.reverseNeed().strip();
        var existing = analyses.findByRequestKey(request.requestKey());
        if (existing.isPresent()) {
            VideoAnalysis prior = existing.get();
            if (!prior.getAssetId().equals(request.assetId()) || !prior.getMode().equals(request.mode())
                    || !Objects.equals(prior.getReverseNeed(), need)) {
                throw BizException.of(ErrorCode.CONFLICT, "请求编号已用于不同的分析，请重新提交");
            }
            return view(prior, true);
        }
        if (!"VIDEO".equals(asset.getType()) || "INVALID".equals(asset.getStatus())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请使用有效的视频素材");
        }
        var object = storage.stat(asset.getStorageKey())
                .orElseThrow(() -> BizException.of(ErrorCode.ASSET_UPLOAD_FAILED, "视频尚未上传完成"));
        checkSize(object.sizeBytes());
        asset.setSizeBytes(object.sizeBytes());
        // This module verifies real metadata in its own worker before calling a model.
        if (!"READY".equals(asset.getStatus())) asset.setStatus("VERIFYING");
        var analysis = new VideoAnalysis();
        analysis.setTenantId(TenantContext.require());
        analysis.setCreatedBy(userId);
        analysis.setRequestKey(request.requestKey());
        analysis.setName(asset.getName());
        analysis.setMode(request.mode());
        analysis.setReverseNeed(need);
        analysis.setAssetId(asset.getId());
        analyses.saveAndFlush(analysis);
        Task task = tasks.submit(VideoAnalysisPrepareHandler.TYPE, "MEDIA_CPU", Map.of("analysisId", analysis.getId()),
                "video-analysis-prepare-" + analysis.getId(), userId);
        analysis.setTaskId(task.getId());
        return view(analysis, true);
    }

    private void checkSize(long size) {
        if (size <= 0 || size > config.getMaxBytes()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "视频大小必须在 0 到 " + config.getMaxBytes() / 1024 / 1024 + " MB 之间");
        }
    }

    @Transactional
    public VideoAnalysisDtos.View importUrl(VideoAnalysisDtos.Import request, Long userId) {
        if (!gateway.configured(ModelAlias.VISION_ANALYZER)) throw BizException.of(ErrorCode.VIDEO_NOT_CONFIGURED, "视频反推模型尚未配置");
        String url = request.url().strip();
        try { urlImporter.validate(url); }
        catch (IllegalArgumentException e) { throw BizException.of(ErrorCode.BAD_REQUEST, "请使用公开 HTTPS 视频文件直链"); }
        String need = request.reverseNeed() == null ? "" : request.reverseNeed().strip();
        var existing = analyses.findByRequestKey(request.requestKey());
        if (existing.isPresent()) {
            var prior = existing.get();
            if (!Objects.equals(prior.getSourceUrl(), url) || !prior.getMode().equals(request.mode())
                    || !Objects.equals(prior.getReverseNeed(), need)) throw BizException.of(ErrorCode.CONFLICT, "请求编号已用于不同的分析");
            return view(prior, true);
        }
        var asset = new Asset();
        asset.setTenantId(TenantContext.require()); asset.setCreatedBy(userId);
        asset.setName("链接视频.mp4"); asset.setType("VIDEO"); asset.setMimeType("video/mp4");
        asset.setStorageKey("t" + asset.getTenantId() + "/video/" + UUID.randomUUID());
        asset.setStatus("VERIFYING");
        assets.saveAndFlush(asset);
        var a = new VideoAnalysis();
        a.setTenantId(asset.getTenantId()); a.setCreatedBy(userId); a.setAssetId(asset.getId());
        a.setName(asset.getName()); a.setMode(request.mode()); a.setReverseNeed(need);
        a.setRequestKey(request.requestKey()); a.setSourceUrl(url);
        analyses.saveAndFlush(a);
        var task = tasks.submit(VideoAnalysisPrepareHandler.TYPE, "MEDIA_CPU", Map.of("analysisId", a.getId()),
                "video-analysis-prepare-" + a.getId(), userId);
        a.setTaskId(task.getId());
        return view(a, true);
    }

    @Transactional(readOnly = true)
    public VideoAnalysisDtos.View get(Long id) { return view(find(id), true); }

    @Transactional(readOnly = true)
    public PageResult<VideoAnalysisDtos.View> list(int page, int size) {
        if (page < 0 || size < 1 || size > 50) throw BizException.of(ErrorCode.BAD_REQUEST, "分页参数无效");
        var result = analyses.findAllByOrderByIdDesc(PageRequest.of(page, size));
        return PageResult.of(result, result.getContent().stream().map(a -> view(a, false)).toList());
    }

    @Transactional
    public VideoAnalysisDtos.View rename(Long id, VideoAnalysisDtos.Rename request) {
        var analysis = analyses.lock(id).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "分析记录不存在"));
        analysis.setName(request.name().strip());
        return view(analysis, false);
    }

    @Transactional(readOnly = true)
    public VideoAnalysis processing(Long id) { return find(id); }

    @Transactional(readOnly = true)
    public String sourceKey(Long id) {
        return assets.findById(find(id).getAssetId()).orElseThrow().getStorageKey();
    }

    @Transactional
    public boolean begin(Long id, Task task, String status, int progress) {
        var analysis = owned(id, task);
        if (analysis == null) return false;
        analysis.setStatus(status);
        analysis.setProgress(progress);
        analysis.setErrorMessage(null);
        return true;
    }

    @Transactional
    public boolean prepared(Long id, Task task, VideoFrameExtractor.Extracted media, List<VideoAnalysis.Frame> frames, String audioKey) {
        var analysis = owned(id, task);
        if (analysis == null) return false;
        analysis.setWidth(media.width()); analysis.setHeight(media.height()); analysis.setDurationMs(media.durationMs());
        analysis.setFrames(frames);
        analysis.setHasAudio(media.hasAudio()); analysis.setAudioStorageKey(audioKey);
        var asset = assets.findById(analysis.getAssetId()).orElseThrow();
        asset.setWidth(media.width()); asset.setHeight(media.height()); asset.setDurationMs(media.durationMs());
        asset.setStatus("READY");
        if (asset.getSizeBytes() == null) {
            asset.setSizeBytes(storage.stat(asset.getStorageKey()).orElseThrow().sizeBytes());
        }
        if (audioKey != null && gateway.configured(ModelAlias.AUDIO_ANALYZER)) {
            analysis.setStatus("ANALYZING_AUDIO"); analysis.setProgress(45);
            Task next = tasks.submit(VideoAnalysisAudioHandler.TYPE, "DEFAULT", Map.of("analysisId", id),
                    "video-analysis-audio-" + id, analysis.getCreatedBy());
            analysis.setTaskId(next.getId());
        } else {
            analysis.setAudioAnalysis(!media.hasAudio()
                    ? VideoAudioOutput.unavailable("NO_AUDIO", "视频没有音轨，本次仅分析画面")
                    : gateway.configured(ModelAlias.AUDIO_ANALYZER)
                        ? VideoAudioOutput.unavailable("FAILED", "音轨提取未完成，本次仅分析画面")
                        : VideoAudioOutput.unavailable("NOT_CONFIGURED", "声音分析暂未开通，本次仅分析画面"));
            enqueueSynthesis(analysis);
        }
        return true;
    }

    @Transactional
    public boolean audioReady(Long id, Task task, Map<String, Object> report) {
        var analysis = owned(id, task);
        if (analysis == null) return false;
        analysis.setAudioAnalysis(report);
        enqueueSynthesis(analysis);
        return true;
    }

    private void enqueueSynthesis(VideoAnalysis analysis) {
        analysis.setStatus("ANALYZING"); analysis.setProgress(65);
        Task next = tasks.submit(VideoAnalysisSynthesizeHandler.TYPE, "DEFAULT", Map.of("analysisId", analysis.getId()),
                "video-analysis-synthesize-" + analysis.getId(), analysis.getCreatedBy());
        analysis.setTaskId(next.getId());
    }

    @Transactional
    public boolean completed(Long id, Task task, Map<String, Object> result) {
        var analysis = owned(id, task);
        if (analysis == null) return false;
        analysis.setResult(result); analysis.setStatus("SUCCEEDED"); analysis.setProgress(100);
        return true;
    }

    @Transactional
    public void failed(Long id, Task task, String message) {
        var analysis = owned(id, task);
        if (analysis == null) return;
        analysis.setStatus("FAILED"); analysis.setErrorMessage(message);
        assets.findById(analysis.getAssetId()).filter(a -> !"READY".equals(a.getStatus()))
                .ifPresent(a -> a.setStatus("INVALID"));
    }

    private VideoAnalysis owned(Long id, Task task) {
        if (taskRepository.findOwnedRunning(task.getId(), task.getAttempts()).isEmpty()) return null;
        var analysis = analyses.lock(id).orElseThrow();
        return analysis.terminal() || !Objects.equals(analysis.getTaskId(), task.getId()) ? null : analysis;
    }

    private VideoAnalysis find(Long id) {
        return analyses.findById(id).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "分析记录不存在"));
    }

    private VideoAnalysisDtos.View view(VideoAnalysis a, boolean detail) {
        String status = a.getStatus();
        String error = a.getErrorMessage();
        if (!a.terminal() && a.getTaskId() != null) {
            var task = taskRepository.findById(a.getTaskId()).orElse(null);
            if (task != null && (task.getStatus() == TaskStatus.FAILED || task.getStatus() == TaskStatus.CANCELLED)) {
                status = "FAILED";
                error = "分析任务未能完成，请重新分析";
            }
        }
        String videoUrl = detail && a.getDurationMs() != null ? assets.findById(a.getAssetId())
                .map(asset -> storage.presignGet(asset.getStorageKey(), Duration.ofMinutes(30))).orElse(null) : null;
        List<VideoAnalysisDtos.FrameView> frames = detail ? a.getFrames().stream()
                .map(frame -> new VideoAnalysisDtos.FrameView(frame.seconds(), storage.presignGet(frame.storageKey(), Duration.ofMinutes(30))))
                .toList() : List.of();
        return new VideoAnalysisDtos.View(a.getId(), a.getAssetId(), a.getName(), a.getMode(), status, a.getProgress(), a.getReverseNeed(),
                a.getDurationMs(), a.getWidth(), a.getHeight(), videoUrl, frames, detail ? a.getResult() : null, error, a.getCreatedAt());
    }
}
