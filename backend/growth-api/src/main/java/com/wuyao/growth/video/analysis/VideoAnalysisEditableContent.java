package com.wuyao.growth.video.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.task.NonRetryableTaskException;

import java.util.*;
import java.util.regex.Pattern;

/** Editable words must be linked to templates and grounded in the supplied audio evidence. */
final class VideoAnalysisEditableContent {
    private static final Pattern TOKEN = Pattern.compile("\\{\\{edit:(e[1-9][0-9]?)}}");
    private VideoAnalysisEditableContent() {}

    static List<Map<String, Object>> validate(JsonNode root, double duration, boolean real) {
        JsonNode items = root.path("editableContent");
        if (!items.isArray() || items.size() > 30) invalid();
        var ids = new HashSet<String>();
        var result = new ArrayList<Map<String, Object>>();
        for (var item : items) {
            String id = text(item, "id", 3), kind = text(item, "kind", 20), source = text(item, "source", 20);
            if (!id.matches("e[1-9][0-9]?") || !ids.add(id)
                    || !Set.of("brand", "dialogue", "subtitle", "text").contains(kind)
                    || !Set.of("audio", "video").contains(source)
                    || ("dialogue".equals(kind) && !"audio".equals(source))) invalid();
            String label = text(item, "label", 60), original = text(item, "original", 1000);
            if (original.contains("{{") || !item.path("start").isNumber() || !item.path("end").isNumber()) invalid();
            double start = item.path("start").asDouble(), end = item.path("end").asDouble();
            if (!Double.isFinite(start) || !Double.isFinite(end) || start < 0 || end <= start || end > duration + 0.05) invalid();
            String token = "{{edit:" + id + "}}";
            if (!root.path("prompt").asText().contains(token) || (!real && !root.path("reuseScript").asText().contains(token))) invalid();
            result.add(Map.of("id", id, "kind", kind, "source", source, "label", label, "original", original, "start", start, "end", end));
        }
        for (String key : real ? List.of("prompt") : List.of("prompt", "reuseScript")) {
            String value = root.path(key).asText();
            var matcher = TOKEN.matcher(value);
            while (matcher.find()) if (!ids.contains(matcher.group(1))) invalid();
            if (TOKEN.matcher(value).replaceAll("").contains("{{")) invalid();
        }
        return result;
    }

    static void validateAudio(List<Map<String, Object>> items, Map<String, Object> audio, ObjectMapper json) {
        JsonNode report = json.valueToTree(audio);
        for (var item : items) {
            if (!"audio".equals(item.get("source"))) continue;
            if (!"ANALYZED".equals(report.path("status").asText())) invalid();
            boolean found = false;
            for (var segment : report.path("transcript")) {
                if (segment.path("text").asText().contains(item.get("original").toString())
                        && ((Number) item.get("start")).doubleValue() < segment.path("end").asDouble() + 0.15
                        && ((Number) item.get("end")).doubleValue() > segment.path("start").asDouble() - 0.15) found = true;
            }
            if (!found) invalid();
        }
    }

    private static String text(JsonNode node, String key, int max) {
        if (!node.path(key).isTextual() || node.path(key).asText().isBlank() || node.path(key).asText().length() > max) invalid();
        return node.path(key).asText();
    }
    private static void invalid() {
        throw new NonRetryableTaskException("VIDEO_ANALYSIS_EDITABLE", "模型未返回与原文对应的可修改内容，请重新分析", null);
    }
}
