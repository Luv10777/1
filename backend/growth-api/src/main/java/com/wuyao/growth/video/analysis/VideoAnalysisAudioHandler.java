package com.wuyao.growth.video.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.gateway.*;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.*;
import com.wuyao.growth.common.web.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class VideoAnalysisAudioHandler implements TaskHandler {
    public static final String TYPE = "VIDEO_ANALYSIS_AUDIO";
    private final VideoAnalysisService service;
    private final ObjectStorage storage;
    private final AiGateway gateway;
    private final VideoAnalysisProperties config;
    private final ObjectMapper json;

    @Override public String type() { return TYPE; }

    @Override public Map<String, Object> handle(Task task) {
        long id = ((Number) task.getPayload().get("analysisId")).longValue();
        if (!service.begin(id, task, "ANALYZING_AUDIO", 50)) return Map.of("status", "STALE");
        var analysis = service.processing(id);
        Map<String, Object> report;
        if (!gateway.configured(ModelAlias.AUDIO_ANALYZER)) {
            report = VideoAudioOutput.unavailable("NOT_CONFIGURED", "声音分析暂未开通，本次仅分析画面");
        } else {
            try {
                byte[] bytes = storage.read(analysis.getAudioStorageKey(), config.getMaxDurationSeconds() * 32_000 + 4096);
                String data = "data:audio/wav;base64," + Base64.getEncoder().encodeToString(bytes);
                var response = gateway.invokeReal(new ProviderRequest(ModelAlias.AUDIO_ANALYZER, task.getTenantId(),
                        "请分析这段音轨。视频总时长为 " + analysis.getDurationMs() / 1000D + " 秒。",
                        Map.of("system", VideoAudioOutput.SYSTEM, "audioDataUrl", data), "video-analysis-audio-" + id));
                if (!response.succeeded()) throw new IllegalStateException("音频模型未返回结果");
                report = VideoAudioOutput.validate(response.output(), analysis.getDurationMs(), json);
            } catch (RuntimeException e) {
                // Audio failures must not block visual analysis or expose provider bodies.
                String code = e instanceof BizException b ? b.getErrorCode().name()
                        : e instanceof NonRetryableTaskException n ? n.errorCode() : "VIDEO_AUDIO_FAILURE";
                log.warn("声音分析降级为画面分析: analysisId={} code={} exception={}", id, code, e.getClass().getSimpleName());
                report = VideoAudioOutput.unavailable("FAILED", "声音分析未完成，本次仅返回画面报告，可重新分析");
            }
        }
        boolean saved = service.audioReady(id, task, report);
        return Map.of("status", saved ? "AUDIO_READY" : "STALE", "audioStatus", report.get("status"));
    }
}
