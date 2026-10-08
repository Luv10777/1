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
        assertThat(result).containsEntry("schemaVersion", 4).containsKey("recreation");
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

    @Test void ChinesePromptsKeepAbbreviationsWhileUsingTheUsersProductReference() {
        var output = productVideo("real");
        String prompt = "生成一段竖屏 AI 视频，人物从右侧拿起如图中产品，外观依据用户上传的商品参考图，镜头固定，窗边柔和侧光，动作自然连续。";
        output.put("prompt", prompt);
        assertThat(VideoAnalysisOutput.validate(output, 12000, "real", new ObjectMapper())).containsEntry("prompt", prompt);
    }

    @Test void productTemplatesPreserveOriginalObservationsAndRealFilmingInstructions() {
        for (String mode : List.of("ai", "real")) {
            var result = VideoAnalysisOutput.validate(productVideo(mode), 12000, mode, new ObjectMapper());
            assertThat(result.get("summary").toString()).contains("某牌白色陶瓷杯子");
            assertThat(result.get("productReferences")).isEqualTo(List.of("某牌白色陶瓷杯子", "某牌", "杯子", "白杯"));
            if ("real".equals(mode)) assertThat(result.get("reuseScript").toString()).contains("白杯");
            else assertThat(result.get("reuseScript").toString()).contains("如图中产品").doesNotContain("白杯");
        }
    }

    @Test void originalProductIdentitiesAreRejectedInEveryGenerationFieldInBothModes() {
        for (String mode : List.of("ai", "real")) {
            var fields = new ArrayList<>(List.of("prompt", "negativePrompt", "shotPrompt", "firstFramePrompt", "continuity", "keyframePrompt", "workflow"));
            if ("ai".equals(mode)) fields.add("reuseScript");
            for (String field : fields) {
                var output = productVideo(mode);
                String leaked = "如图中产品为某牌白色陶瓷杯子，人物从右侧伸手拿起白杯。";
                switch (field) {
                    case "shotPrompt", "firstFramePrompt", "continuity" -> ((List<Map<String, Object>>) output.get("shots"))
                            .getFirst().put(field.equals("shotPrompt") ? "prompt" : field, leaked);
                    case "keyframePrompt" -> output.put("keyframes", List.of(Map.of("seconds", 0, "title", "首帧", "description", "白杯", "prompt", leaked)));
                    case "workflow" -> ((Map<String, Object>) output.get("recreation")).put("real".equals(mode) ? "aiWorkflow" : "workflow", leaked);
                    default -> output.put(field, leaked);
                }
                assertThatThrownBy(() -> VideoAnalysisOutput.validate(output, 12000, mode, new ObjectMapper()))
                        .as("%s mode, %s", mode, field).isInstanceOfSatisfying(NonRetryableTaskException.class,
                                error -> assertThat(error.errorCode()).isEqualTo("VIDEO_ANALYSIS_PRODUCT"));
            }
        }
    }

    @Test void productVideosRequireTheReferencePlaceholderInTheOverallPromptAndAiScript() {
        for (String field : List.of("prompt", "reuseScript")) {
            var output = productVideo("ai"); output.put(field, "一段竖屏视频，窗边柔光，相机缓慢向前移动。");
            assertThatThrownBy(() -> VideoAnalysisOutput.validate(output, 12000, "ai", new ObjectMapper()))
                    .isInstanceOfSatisfying(NonRetryableTaskException.class,
                            error -> assertThat(error.errorCode()).isEqualTo("VIDEO_ANALYSIS_PRODUCT"));
        }
        var output = productVideo("ai"); output.remove("productReferences"); assertModeInvalid(output, "ai");
        output = productVideo("ai"); output.put("productReferences", List.of("")); assertModeInvalid(output, "ai");
        // A graphic/landscape video with no product keeps its real subject and does not invent a product reference.
        assertThat(VideoAnalysisOutput.validate(validFor("ai"), 12000, "ai", new ObjectMapper()).get("prompt").toString())
                .doesNotContain("如图中产品");
    }

    static Map<String, Object> productVideo(String mode) {
        var output = validFor(mode);
        output.put("summary", "某牌白色陶瓷杯子放在窗边。");
        output.put("productReferences", List.of("某牌白色陶瓷杯子", "某牌", "杯子", "白杯"));
        output.put("prompt", "如图中产品位于木桌中央，人物从右侧伸手拿起，窗边柔光，竖屏视频。");
        output.put("reuseScript", "real".equals(mode) ? "手机固定在白杯前方，先拍白杯再拍人物拿起。"
                : "镜头一｜零至六秒｜如图中产品位于木桌中央｜人物从右侧拿起｜相机固定｜结束时商品被拿起｜接下一镜。");
        for (var shot : (List<Map<String, Object>>) output.get("shots")) {
            shot.put("scene", "白杯放在木桌中央，人物从右侧伸手拿起杯子。");
            shot.put("prompt", "竖屏六秒，如图中产品在木桌中央，人物从右侧伸手拿起，镜头固定，窗边柔光。");
            shot.put("firstFramePrompt", "如图中产品在木桌中央，人物尚未伸手，窗边柔光，竖屏静态画面。");
            shot.put("continuity", "如图中产品依据用户商品参考图保持外观一致。");
            if ("real".equals(mode)) shot.put("filming", "手机固定在白杯前方，人物从右侧伸手拿起杯子。");
        }
        output.put("keyframes", List.of(Map.of("seconds", 0, "title", "窗边白杯", "description", "白杯在木桌中央",
                "prompt", "竖屏静态画面，木桌中央的如图中产品，窗边柔光。")));
        return output;
    }

    private void assertModeInvalid(Map<String, Object> output, String mode) {
        assertThatThrownBy(() -> VideoAnalysisOutput.validate(output, 12000, mode, new ObjectMapper()))
                .isInstanceOf(NonRetryableTaskException.class);
    }

    static Map<String, Object> validFor(String mode) {
        var result = valid();
        result.put("productReferences", List.of());
        result.put("editableContent", List.of());
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
