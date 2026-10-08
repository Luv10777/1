package com.wuyao.growth.video.analysis;

import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.*;
import com.wuyao.growth.common.gateway.AiGateway;
import com.wuyao.growth.common.gateway.ModelAlias;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Component
@RequiredArgsConstructor
public class VideoAnalysisPrepareHandler implements TaskHandler {
    public static final String TYPE = "VIDEO_ANALYSIS_PREPARE";
    private final VideoAnalysisService service;
    private final VideoFrameExtractor extractor;
    private final ObjectStorage storage;
    private final VideoAnalysisProperties config;
    private final AiGateway gateway;
    private final com.wuyao.growth.video.VideoAnalysisUrlImporter urlImporter;

    @Override public String type() { return TYPE; }
    @Override public Map<String, Object> handle(Task task) {
        long id = ((Number) task.getPayload().get("analysisId")).longValue();
        Path directory = null;
        List<VideoAnalysis.Frame> uploaded = new ArrayList<>();
        List<String> uploadedKeys = new ArrayList<>();
        boolean retained = false;
        try {
            if (!service.begin(id, task, "EXTRACTING", 15)) return Map.of("status", "STALE");
            directory = Files.createTempDirectory("wuyao-video-analysis-");
            Path video = directory.resolve("input.mp4");
            var analysis = service.processing(id);
            String sourceKey = service.sourceKey(id);
            if (analysis.getSourceUrl() == null) {
                storage.download(sourceKey, video, Math.toIntExact(config.getMaxBytes()));
            } else {
                urlImporter.download(analysis.getSourceUrl(), video, config.getMaxBytes());
            }
            var media = extractor.extract(video, directory, gateway.configured(ModelAlias.AUDIO_ANALYZER));
            if (analysis.getSourceUrl() != null) {
                try (var input = Files.newInputStream(video)) { storage.put(sourceKey, input, Files.size(video), "video/mp4"); }
            }
            String prefix = "t" + task.getTenantId() + "/video-analysis/" + id + "/" + UUID.randomUUID() + "/";
            for (var frame : media.frames()) {
                String key = prefix + frame.path().getFileName();
                uploaded.add(new VideoAnalysis.Frame(frame.seconds(), key));
                uploadedKeys.add(key);
                storage.put(key, Files.readAllBytes(frame.path()), "image/jpeg");
            }
            String audioKey = null;
            if (media.audio() != null) {
                audioKey = prefix + "audio.wav";
                uploadedKeys.add(audioKey);
                storage.put(audioKey, Files.readAllBytes(media.audio()), "audio/wav");
            }
            retained = service.prepared(id, task, media, uploaded, audioKey);
            return Map.of("status", retained ? "PREPARED" : "STALE", "frameCount", media.frames().size());
        } catch (NonRetryableTaskException e) {
            service.failed(id, task, e.getMessage());
            throw e;
        } catch (Exception e) {
            String message = "视频读取或抽帧失败，请检查对象存储和媒体处理服务";
            if (task.getAttempts() >= task.getMaxAttempts()) service.failed(id, task, message);
            throw new IllegalStateException(message);
        } finally {
            if (!retained) for (String key : uploadedKeys) {
                try { storage.delete(key); } catch (RuntimeException ignored) { /* Retryable storage cleanup. */ }
            }
            if (directory != null) {
                try (var paths = Files.walk(directory)) {
                    for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
                } catch (Exception ignored) { /* Only this worker's unique temporary directory is removed. */ }
            }
        }
    }
}
