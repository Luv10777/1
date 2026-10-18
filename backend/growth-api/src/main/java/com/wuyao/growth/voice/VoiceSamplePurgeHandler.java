package com.wuyao.growth.voice;

import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 商户关闭后删除一个声音样本的文件、试听音频和远端音色。可以重复执行：已删的部分会跳过。 */
@Component
@RequiredArgsConstructor
public class VoiceSamplePurgeHandler implements TaskHandler {
    public static final String TYPE = "VOICE_SAMPLE_PURGE";
    private final VoiceService voices;

    @Override public String type() { return TYPE; }

    @Override
    public Map<String, Object> handle(Task task) {
        Long sampleId = ((Number) task.getPayload().get("sampleId")).longValue();
        voices.purge(sampleId);
        return Map.of("status", "PURGED");
    }
}
