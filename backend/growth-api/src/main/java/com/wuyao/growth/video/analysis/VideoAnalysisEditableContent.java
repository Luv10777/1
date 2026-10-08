package com.wuyao.growth.video.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.task.NonRetryableTaskException;

import java.util.*;
import java.util.regex.Pattern;

/** Normalize equivalent field labels; editable words still need template links and audio evidence. */
final class VideoAnalysisEditableContent {
    private static final Pattern TOKEN = Pattern.compile("\\{\\{edit:(e[1-9][0-9]?)}}");
    private VideoAnalysisEditableContent() {}

    static List<Map<String, Object>> validate(JsonNode root, double duration, boolean real) {
        JsonNode items = root.path("editableContent");
        if (!items.isArray() || items.size() > 60) invalid("可修改内容列表格式不完整");
        var ids = new HashSet<String>();
        var result = new ArrayList<Map<String, Object>>();
        for (var item : items) {
            String id = text(item, "id", 10).strip();
            String kind = normalizeKind(text(item, "kind", 20)), source = normalizeSource(text(item, "source", 20));
            if (!id.matches("e[1-9][0-9]?") || !ids.add(id)) invalid("可修改内容编号不正确或重复");
            if (!Set.of("brand", "dialogue", "subtitle", "text").contains(kind)) invalid("可修改内容类型不正确");
            if (!Set.of("audio", "video").contains(source)) invalid("可修改内容来源不明确");
            if ("dialogue".equals(kind) && !"audio".equals(source)) invalid("口播来源必须是已分析的音频");
            String label = text(item, "label", 60), original = text(item, "original", 2000);
            if (original.contains("{{")) invalid("可修改内容原文包含内部标记");
            if (!item.path("start").isNumber() || !item.path("end").isNumber()) invalid("可修改内容时间格式不正确");
            double start = item.path("start").asDouble(), end = item.path("end").asDouble();
            if (!Double.isFinite(start) || !Double.isFinite(end) || start < 0 || end <= start || end > duration + 0.05)
                invalid("可修改内容时间范围不正确");
            String token = "{{edit:" + id + "}}";
            if (!root.path("prompt").asText().contains(token) || (!real && !root.path("reuseScript").asText().contains(token)))
                invalid("可修改内容未关联到提示词和分镜脚本");
            result.add(Map.of("id", id, "kind", kind, "source", source, "label", label, "original", original, "start", start, "end", end));
        }
        for (String key : real ? List.of("prompt") : List.of("prompt", "reuseScript")) {
            String value = root.path(key).asText();
            var matcher = TOKEN.matcher(value);
            while (matcher.find()) if (!ids.contains(matcher.group(1))) invalid("提示词或分镜引用了不存在的可修改内容");
            if (TOKEN.matcher(value).replaceAll("").contains("{{")) invalid("提示词或分镜的可修改内容标记不完整");
        }
        return result;
    }

    static void validateAudio(List<Map<String, Object>> items, Map<String, Object> audio, ObjectMapper json) {
        JsonNode report = json.valueToTree(audio);
        for (var item : items) {
            if (!"audio".equals(item.get("source"))) continue;
            if (!"ANALYZED".equals(report.path("status").asText())) invalid("尚无已分析的音频支持这段口播");
            boolean found = false;
            for (var segment : report.path("transcript")) {
                if (segment.path("text").asText().contains(item.get("original").toString())
                        && ((Number) item.get("start")).doubleValue() < segment.path("end").asDouble() + 0.15
                        && ((Number) item.get("end")).doubleValue() > segment.path("start").asDouble() - 0.15) found = true;
            }
            if (!found) invalid("口播原文或出现时间与已识别音频不一致");
        }
    }

    private static String text(JsonNode node, String key, int max) {
        if (!node.path(key).isTextual() || node.path(key).asText().isBlank() || node.path(key).asText().length() > max)
            invalid("可修改内容字段缺失或长度不正确");
        return node.path(key).asText();
    }

    private static String normalizeKind(String value) {
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "brand", "brand_name", "品牌", "品牌名称" -> "brand";
            case "dialogue", "speech", "voiceover", "voice_over", "narration", "口播", "对白", "对话", "台词", "配音" -> "dialogue";
            case "subtitle", "subtitles", "caption", "captions", "字幕", "画面字幕" -> "subtitle";
            case "text", "price", "promotion", "contact", "contact_info", "价格", "活动", "联系方式", "其他文字" -> "text";
            default -> value;
        };
    }

    private static String normalizeSource(String value) {
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "audio", "speech", "audio_transcript", "音频", "音轨", "声音" -> "audio";
            case "video", "visual", "image", "frame", "视频", "画面" -> "video";
            default -> value;
        };
    }

    private static void invalid(String reason) {
        throw new NonRetryableTaskException("VIDEO_ANALYSIS_EDITABLE", "品牌、口播或字幕整理失败：" + reason + "，请重新分析", null);
    }
}
