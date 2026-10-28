package com.wuyao.growth.copywriting;

import com.wuyao.growth.common.task.*;
import com.wuyao.growth.common.web.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class CopyVoiceProfileHandler implements TaskHandler {
    public static final String TYPE = "COPY_VOICE_PROFILE";
    private final CopyVoiceService service;
    private final CopyModel model;

    @Override public String type() { return TYPE; }
    @Override public Map<String, Object> handle(Task task) {
        long storeId = ((Number) task.getPayload().get("storeId")).longValue();
        int revision = ((Number) task.getPayload().get("revision")).intValue();
        try {
            var voice = service.processing(storeId);
            if (voice.getRevision() != revision) return Map.of("status", "STALE");
            var output = model.ask(task.getTenantId(), CopyPrompt.VOICE_SYSTEM, CopyPrompt.voicePrompt(voice.getSamples()),
                    List.of(), 600, "copy-voice-" + voice.getId() + "-" + revision);
            return Map.of("status", service.learned(storeId, revision, CopyPrompt.voice(output)) ? "SUCCEEDED" : "STALE");
        } catch (NonRetryableTaskException e) {
            service.learnFailed(storeId, revision, e.getMessage());
            throw e;
        } catch (Exception e) {
            String message = e instanceof BizException ? e.getMessage() : "口吻没有学成，请稍后再试";
            if (task.getAttempts() >= task.getMaxAttempts()) service.learnFailed(storeId, revision, message);
            throw new IllegalStateException(message);
        }
    }
}
