package com.wuyao.growth.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the text model is asked to pull out of a document, and how its answer is read back. A brand's
 * document may fill the brand's descriptive fields, a store's the store's contact fields; both may yield
 * questions and answers. Only text the document states is wanted, and every value fits its field.
 */
final class DocumentDigest {

    record Field(String key, String label, int maxLength) {
    }

    static final List<Field> BRAND_FIELDS = List.of(
            new Field("slogan", "品牌口号", 120),
            new Field("intro", "品牌简介", 1000),
            new Field("positioning", "品牌定位", 500),
            new Field("targetAudience", "目标人群", 500),
            new Field("languageStyle", "语言风格", 500),
            new Field("history", "发展历程", 2000),
            new Field("brandStory", "品牌故事", 4000),
            new Field("coreTeam", "核心团队", 2000),
            new Field("culture", "品牌文化", 2000));

    static final List<Field> STORE_FIELDS = List.of(
            new Field("address", "地址", 300),
            new Field("phone", "电话", 40),
            new Field("businessHours", "营业时间", 120),
            new Field("transportGuide", "交通指引", 500));

    static final int MAX_QUESTIONS = 30;
    private static final int MAX_QUESTION_LENGTH = 200;
    private static final int MAX_ANSWER_LENGTH = 300;

    record Result(Map<String, String> fields, List<Map.Entry<String, String>> questions) {
    }

    private DocumentDigest() {
    }

    static List<Field> fieldsFor(boolean brand) {
        return brand ? BRAND_FIELDS : STORE_FIELDS;
    }

    static Field field(boolean brand, String key) {
        return fieldsFor(brand).stream().filter(field -> field.key().equals(key)).findFirst().orElse(null);
    }

    static String system(boolean brand) {
        StringBuilder fields = new StringBuilder();
        for (Field field : fieldsFor(brand)) {
            fields.append("- ").append(field.key()).append("：").append(field.label())
                    .append("，不超过 ").append(field.maxLength()).append(" 字\n");
        }
        return """
                你在帮一家实体店整理资料。用户会给你一份%s的文档原文，请从中整理两样东西：

                1. 资料字段。只填文档里明确写到的内容，用文档自己的说法，可以压缩但不要加工夸大；文档没写到的字段不要出现。可填的字段：
                %s
                2. 顾客常问的问题和回答，最多 %d 条。只挑文档里能找到答案的；问题写成顾客会怎么问，回答写成直播时可以直接说出口的话，口语、简短，%d 字以内。不要编造文档里没有的价格、时间、数字和承诺。

                只输出一个 JSON 对象，不要任何解释：
                {"fields": {"字段名": "内容"}, "faqs": [{"question": "问题", "answer": "回答"}]}
                """.formatted(brand ? "品牌" : "门店", fields, MAX_QUESTIONS, MAX_ANSWER_LENGTH);
    }

    static String prompt(String documentName, String text) {
        return "文档名称：" + documentName + "\n\n文档原文：\n" + text;
    }

    /** @return null when the reply holds no JSON object at all */
    static Result parse(ObjectMapper json, String reply, boolean brand) {
        if (reply == null) return null;
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        JsonNode root;
        try {
            root = json.readTree(reply.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        JsonNode given = root.path("fields");
        for (Field field : fieldsFor(brand)) {
            String value = clean(given.path(field.key()).asText(""), field.maxLength());
            if (!value.isEmpty()) fields.put(field.key(), value);
        }
        List<Map.Entry<String, String>> questions = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode faq : root.path("faqs")) {
            String question = clean(faq.path("question").asText(""), MAX_QUESTION_LENGTH);
            String answer = clean(faq.path("answer").asText(""), MAX_ANSWER_LENGTH);
            if (question.isEmpty() || answer.isEmpty() || !seen.add(question.replaceAll("[\\p{P}\\s]", ""))) continue;
            questions.add(Map.entry(question, answer));
            if (questions.size() == MAX_QUESTIONS) break;
        }
        return new Result(fields, questions);
    }

    private static String clean(String value, int maxLength) {
        String trimmed = value == null ? "" : value.strip();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
