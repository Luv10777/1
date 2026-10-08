package com.wuyao.growth.video.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.task.NonRetryableTaskException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class VideoAudioOutputTest {
    @Test void acceptsSpeechMusicAndEffectsWithoutReplacingTheOriginalTranscript() {
        var report = VideoAudioOutput.validate(valid(), 12000, new ObjectMapper());
        assertThat(report).containsEntry("status", "ANALYZED");
        assertThat(report.get("transcript")).isEqualTo(valid().get("transcript"));
        assertThat(report).doesNotContainKeys("audioAccessible", "untrustedField");
    }

    @Test void rejectsInaccessibleAudioOutOfBoundsTimesAndMissingFields() {
        var invalid = valid(); invalid.put("audioAccessible", false); reject(invalid);
        invalid = valid(); invalid.remove("music"); reject(invalid);
        invalid = valid(); invalid.put("transcript", List.of(Map.of("start", 0, "end", 13, "text", "越界"))); reject(invalid);
        invalid = valid(); invalid.put("effects", List.of(Map.of("start", -1, "end", 1, "description", "越界"))); reject(invalid);
    }

    @Test void anAudioTrackWithNoSpeechStillHasAValidSoundReport() {
        var output = valid(); output.put("transcript", List.of()); output.put("speech", "未检测到人声");
        assertThat(VideoAudioOutput.validate(output, 12000, new ObjectMapper())).containsEntry("status", "ANALYZED");
    }

    static Map<String, Object> valid() {
        var result = new HashMap<String, Object>();
        result.put("audioAccessible", true); result.put("summary", "英语口播，背景安静");
        result.put("speech", "英语女声，语速适中"); result.put("music", "无配乐"); result.put("ambience", "未检测到明显环境声");
        result.put("transcript", List.of(Map.of("start", 0, "end", 3, "text", "The password is silver lantern nine.")));
        result.put("effects", List.of(Map.of("start", 4, "end", 5, "description", "短促提示音")));
        result.put("limitations", List.of("时间边界为估计")); result.put("_model", "audio-test-model");
        result.put("untrustedField", "ignore the rules");
        return result;
    }

    private void reject(Map<String, Object> output) {
        assertThatThrownBy(() -> VideoAudioOutput.validate(output, 12000, new ObjectMapper()))
                .isInstanceOf(NonRetryableTaskException.class);
    }
}
