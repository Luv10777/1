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

    @Test void aiResultsRequireExecutableGenerationStepsAndPerShotPrompts() {
        var result = VideoAnalysisOutput.validate(validFor("ai"), 12000, "ai", new ObjectMapper());
        assertThat(result).containsEntry("schemaVersion", 2).containsKey("recreation");
        assertThat((Map<?, ?>) result.get("recreation")).hasSize(3);
        var invalid = validFor("ai"); invalid.put("recreation", Map.of("workflow", "生成")); assertModeInvalid(invalid, "ai");
        invalid = validFor("ai");
        var shots = (List<Map<String, Object>>) invalid.get("shots");
        shots.getFirst().remove("firstFramePrompt"); assertModeInvalid(invalid, "ai");
    }

    @Test void realResultsRequireFilmingStepsAndAnIndependentAiConversionPlan() {
        var result = VideoAnalysisOutput.validate(validFor("real"), 12000, "real", new ObjectMapper());
        assertThat((Map<?, ?>) result.get("recreation")).hasSize(6);
        var invalid = validFor("real");
        ((Map<String, Object>) invalid.get("recreation")).remove("aiWorkflow"); assertModeInvalid(invalid, "real");
        invalid = validFor("real");
        ((List<Map<String, Object>>) invalid.get("shots")).getFirst().remove("filming"); assertModeInvalid(invalid, "real");
    }

    @Test void selectingAModeCannotAcceptTheOtherModesContract() {
        assertModeInvalid(validFor("ai"), "real");
        assertModeInvalid(validFor("real"), "ai");
        var invalid = validFor("ai");
        invalid.put("keyframes", List.of(Map.of("seconds", 0, "title", "首帧", "prompt", "物体")));
        assertModeInvalid(invalid, "ai");
    }

    @Test void bothModesRejectEnglishInEveryGenerationPromptBeforeSaving() {
        for (String mode : List.of("ai", "real")) {
            for (String field : List.of("prompt", "negativePrompt", "shotPrompt", "firstFramePrompt", "keyframePrompt")) {
                var invalid = validFor(mode);
                switch (field) {
                    case "shotPrompt", "firstFramePrompt" -> ((List<Map<String, Object>>) invalid.get("shots"))
                            .getFirst().put(field.equals("shotPrompt") ? "prompt" : field, "A red object near the window");
                    case "keyframePrompt" -> invalid.put("keyframes", List.of(Map.of("seconds", 0, "title", "首帧",
                            "description", "桌上的物体", "prompt", "A red object near the window")));
                    default -> invalid.put(field, "A red object near the window");
                }
                assertThatThrownBy(() -> VideoAnalysisOutput.validate(invalid, 12000, mode, new ObjectMapper()))
                        .as("%s mode, %s", mode, field).isInstanceOfSatisfying(NonRetryableTaskException.class, error -> {
                            assertThat(error.errorCode()).isEqualTo("VIDEO_ANALYSIS_LANGUAGE");
                            assertThat(error.getMessage()).contains("中文复刻提示词");
                        });
            }
        }
    }

    @Test void aChineseSuffixCannotDisguiseAnEnglishPrompt() {
        var invalid = validFor("ai");
        invalid.put("prompt", "A cinematic shot of a red object near a window with soft light. 中文");
        assertThatThrownBy(() -> VideoAnalysisOutput.validate(invalid, 12000, "ai", new ObjectMapper()))
                .isInstanceOfSatisfying(NonRetryableTaskException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("VIDEO_ANALYSIS_LANGUAGE"));
    }

    @Test void ChinesePromptsMayKeepNecessaryBrandNamesAndAbbreviations() {
        var output = validFor("real");
        String prompt = "生成一段竖屏 AI 视频，人物从右侧拿起 DJI 产品，镜头固定，窗边柔和侧光，动作自然连续。";
        output.put("prompt", prompt);
        assertThat(VideoAnalysisOutput.validate(output, 12000, "real", new ObjectMapper())).containsEntry("prompt", prompt);
    }

    private void assertModeInvalid(Map<String, Object> output, String mode) {
        assertThatThrownBy(() -> VideoAnalysisOutput.validate(output, 12000, mode, new ObjectMapper()))
                .isInstanceOf(NonRetryableTaskException.class);
    }

    static Map<String, Object> validFor(String mode) {
        var result = valid();
        result.put("prompt", "生成竖屏视频，桌上的红色物体位于画面中央，相机固定，保持自然光线。");
        result.put("negativePrompt", "避免画面模糊、物体变形和画面闪烁。");
        var shots = new ArrayList<Map<String, Object>>();
        for (var shot : (List<Map<String, Object>>) result.get("shots")) {
            var detailed = new HashMap<>(shot);
            for (String key : List.of("framing", "lighting", "sound", "editing", "prompt", "firstFramePrompt", "continuity"))
                detailed.put(key, "建议：用相同参考图逐镜复刻");
            if ("real".equals(mode)) detailed.put("filming", "建议：手机固定在桌旁，人物从右侧伸手");
            shots.add(detailed);
        }
        result.put("shots", shots);
        result.put("keyframes", List.of(Map.of("seconds", 0, "title", "首帧", "description", "可见红色物体", "prompt", "桌上的红色物体")));
        var recreation = new HashMap<String, Object>();
        for (String key : "real".equals(mode) ? List.of("preparation", "cameraSetup", "lightingSetup", "recording", "editing", "aiWorkflow")
                : List.of("workflow", "consistency", "assembly")) recreation.put(key, "按镜头操作的复刻建议");
        result.put("recreation", recreation);
        return result;
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
