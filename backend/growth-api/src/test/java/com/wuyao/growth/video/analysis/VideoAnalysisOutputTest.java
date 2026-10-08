package com.wuyao.growth.video.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.task.NonRetryableTaskException;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class VideoAnalysisOutputTest {
    @Test void acceptedResultsExplicitlyIndicateThatAudioWasNotAnalyzed() {
        var result = VideoAnalysisOutput.validate(valid(), 12000, new ObjectMapper());
        assertThat(result).containsEntry("audioAnalyzed", false);
        assertThat(result).doesNotContainKey("retentionRate");
    }
    @Test void rejectsImpossibleTimesMissingContentAndInvalidScores() {
        var invalid = valid();
        invalid.put("shots", List.of(shot(0, 13)));
        assertInvalid(invalid);
        invalid = valid(); invalid.put("prompt", ""); assertInvalid(invalid);
        invalid = valid(); invalid.put("keyframes", List.of(Map.of("seconds", -1, "title", "a", "prompt", "b"))); assertInvalid(invalid);
        invalid = valid(); invalid.put("shots", List.of(shot(0, 4), shot(6, 12))); assertInvalid(invalid);
        invalid = valid(); invalid.put("dimensions", List.of(Map.of("name", "光影", "score", 200))); assertInvalid(invalid);
    }
    private void assertInvalid(Map<String, Object> output) {
        assertThatThrownBy(() -> VideoAnalysisOutput.validate(output, 12000, new ObjectMapper()))
                .isInstanceOf(NonRetryableTaskException.class);
    }
    static Map<String, Object> valid() {
        var result = new HashMap<String, Object>();
        result.put("summary", "一段视频"); result.put("prompt", "A red object"); result.put("negativePrompt", "blur");
        result.put("reuseScript", "复刻建议"); result.put("retentionRate", 99);
        result.put("parameters", List.of(Map.of("key", "主体", "value", "物体")));
        result.put("dimensions", List.of("光影", "运镜", "主体", "场景", "色彩").stream().map(name -> Map.of("name", name, "score", 80)).toList());
        result.put("shots", List.of(shot(0, 6), shot(6, 12)));
        result.put("keyframes", List.of(Map.of("seconds", 0, "title", "首帧", "prompt", "A red object")));
        result.put("highlights", List.of("清晰")); result.put("suggestions", List.of("提高细节")); result.put("limitations", List.of("无音频"));
        return result;
    }
    private static Map<String, Object> shot(double start, double end) {
        return Map.of("start", start, "end", end, "scene", "场景", "camera", "固定", "emotion", "平静", "pacing", "慢");
    }
}
