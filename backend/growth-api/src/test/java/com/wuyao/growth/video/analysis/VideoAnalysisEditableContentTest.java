package com.wuyao.growth.video.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.task.NonRetryableTaskException;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class VideoAnalysisEditableContentTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void brandsAndActualDialogueAreLinkedWithoutRewritingTheirOriginalText() {
        var output = linked("ai");
        var result = VideoAnalysisOutput.validate(output, 12000, "ai", json);
        var items = (List<Map<String, Object>>) result.get("editableContent");
        assertThat(items).hasSize(2);
        assertThat(items.get(1)).containsEntry("original", "试试旧牌的杯子");
        assertThatCode(() -> VideoAnalysisEditableContent.validateAudio(items, audio(), json)).doesNotThrowAnyException();
        assertThat(result.get("prompt").toString()).contains("{{edit:e1}}", "{{edit:e2}}").doesNotContain("旧牌", "杯子");
    }

    @Test void equivalentLabelsAreNormalizedWithoutChangingOriginalWordsOrEvidence() {
        for (var labels : List.of(List.of(" 品牌 ", " IMAGE ", "口播", "音频"),
                List.of("brand_name", "frame", "VoiceOver", "audio_transcript"))) {
            var output = linked("ai");
            output.put("editableContent", List.of(item(" e1 ", labels.get(0), labels.get(1), "旧牌", 0, 3),
                    item("e2", labels.get(2), labels.get(3), "试试旧牌的杯子", 0, 3)));
            var items = (List<Map<String, Object>>) VideoAnalysisOutput.validate(output, 12000, "ai", json).get("editableContent");
            assertThat(items.getFirst()).containsEntry("id", "e1").containsEntry("kind", "brand").containsEntry("source", "video");
            assertThat(items.get(1)).containsEntry("kind", "dialogue").containsEntry("source", "audio").containsEntry("original", "试试旧牌的杯子");
            assertThatCode(() -> VideoAnalysisEditableContent.validateAudio(items, audio(), json)).doesNotThrowAnyException();
            assertThat(((List<Map<String, Object>>) output.get("editableContent")).getFirst()).containsEntry("kind", labels.get(0));
        }
    }

    @Test void CaptionsAndMerchantBusinessTextUseSupportedTypes() {
        for (String alias : List.of("caption", "字幕", "price", "promotion", "contact_info", "联系方式")) {
            var output = linked("ai");
            output.put("editableContent", List.of(item("e1", alias, "画面", "旧牌", 0, 3),
                    item("e2", "dialogue", "audio", "试试旧牌的杯子", 0, 3)));
            var items = (List<Map<String, Object>>) VideoAnalysisOutput.validate(output, 12000, "ai", json).get("editableContent");
            assertThat(items.getFirst()).containsEntry("kind", Set.of("caption", "字幕").contains(alias) ? "subtitle" : "text")
                    .containsEntry("source", "video");
        }
    }

    @Test void ambiguousSourcesAndVisualDialogueAreNotConvertedIntoAudioEvidence() {
        for (String source : List.of("both", "画面", "image")) {
            var output = linked("ai");
            output.put("editableContent", List.of(item("e1", "brand", "video", "旧牌", 0, 3),
                    item("e2", "speech", source, "试试旧牌的杯子", 0, 3)));
            assertThatThrownBy(() -> VideoAnalysisOutput.validate(output, 12000, "ai", json))
                    .isInstanceOf(NonRetryableTaskException.class).hasMessageContaining("来源")
                    .hasMessageNotContaining("试试旧牌的杯子");
        }
    }

    @Test void everyEditableItemMustBeLinkedToBothAiCopyBlocks() {
        for (String key : List.of("prompt", "reuseScript")) {
            var output = linked("ai");
            output.put(key, output.get(key).toString().replace("{{edit:e2}}", ""));
            reject(output, "ai");
        }
        var output = linked("ai"); output.remove("editableContent"); reject(output, "ai");
    }

    @Test void duplicateUnknownAndMalformedSlotsAreRejected() {
        var output = linked("ai");
        output.put("editableContent", List.of(item("e1", "brand", "video", "旧牌", 0, 3), item("e1", "subtitle", "video", "新字幕", 3, 6)));
        reject(output, "ai");
        for (String token : List.of("{{edit:e9}}", "{{edit:foo}}", "{{edit:e1", "{{brand:e1}}")) {
            output = linked("ai"); output.put("prompt", output.get("prompt") + token); reject(output, "ai");
        }
    }

    @Test void invalidContentSourcesTimesAndKindsAreRejected() {
        for (var invalid : List.of(item("e1", "dialogue", "video", "旧牌", 0, 3),
                item("e1", "brand", "guess", "旧牌", 0, 3), item("e1", "madeup", "video", "旧牌", 0, 3),
                item("e1", "brand", "video", "旧牌", -1, 3), item("e1", "brand", "video", "旧牌", 3, 3),
                item("e1", "brand", "video", "旧牌", 0, 13), item("e0", "brand", "video", "旧牌", 0, 3),
                item("e1", "brand", "video", "{{edit:e1}}", 0, 3))) {
            var output = linked("ai"); output.put("editableContent", List.of(invalid)); reject(output, "ai");
        }
    }

    @Test void speechNeedsAnExactQuoteWithOverlappingTimeInAnAnalyzedAudioReport() {
        var items = (List<Map<String, Object>>) VideoAnalysisOutput.validate(linked("ai"), 12000, "ai", json).get("editableContent");
        for (var audio : List.<Map<String, Object>>of(Map.of("status", "NO_AUDIO"), Map.of("status", "FAILED"),
                Map.of("status", "ANALYZED", "transcript", List.of(Map.of("start", 0, "end", 3, "text", "推测出来的口播"))),
                Map.of("status", "ANALYZED", "transcript", List.of(Map.of("start", 8, "end", 10, "text", "试试旧牌的杯子"))))) {
            assertThatThrownBy(() -> VideoAnalysisEditableContent.validateAudio(items, audio, json))
                    .isInstanceOfSatisfying(NonRetryableTaskException.class,
                            error -> assertThat(error.errorCode()).isEqualTo("VIDEO_ANALYSIS_EDITABLE"));
        }
    }

    @Test void realFilmingScriptKeepsItsObservationsWhileAiPromptMustLinkEveryEdit() {
        var output = linked("real");
        var result = VideoAnalysisOutput.validate(output, 12000, "real", json);
        assertThat(result.get("reuseScript")).isEqualTo(output.get("reuseScript"));
        assertThat(result.get("prompt").toString()).contains("{{edit:e1}}", "{{edit:e2}}");
    }

    static Map<String, Object> linked(String mode) {
        var output = VideoAnalysisOutputTest.productVideo(mode);
        output.put("prompt", output.get("prompt") + " 文字安排：{{edit:e1}} 口播安排：{{edit:e2}}");
        if ("ai".equals(mode)) output.put("reuseScript", output.get("reuseScript") + " {{edit:e1}} {{edit:e2}}");
        output.put("editableContent", List.of(item("e1", "brand", "video", "旧牌", 0, 3), item("e2", "dialogue", "audio", "试试旧牌的杯子", 0, 3)));
        return output;
    }

    private static Map<String, Object> item(String id, String kind, String source, String original, double start, double end) {
        return Map.of("id", id, "kind", kind, "source", source, "label", "开场文案", "original", original, "start", start, "end", end);
    }

    private static Map<String, Object> audio() {
        return Map.of("status", "ANALYZED", "transcript", List.of(Map.of("start", 0, "end", 3, "text", "试试旧牌的杯子")));
    }

    private void reject(Map<String, Object> output, String mode) {
        assertThatThrownBy(() -> VideoAnalysisOutput.validate(output, 12000, mode, json))
                .isInstanceOfSatisfying(NonRetryableTaskException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("VIDEO_ANALYSIS_EDITABLE"));
    }
}
