package com.wuyao.growth.copywriting;

import com.wuyao.growth.common.task.NonRetryableTaskException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class CopyPromptTest {
    @Test void versionsAreTrimmedScoredAndShapedForThePlatform() {
        var output = Map.<String, Object>of("versions", List.of(
                Map.of("angle", "场景描写", "why", "用画面开头", "title", "不该有标题", "body", "  下雨天  ",
                        "tags", List.of("#雨天"), "aiScore", 9, "aiReason", "口语"),
                Map.of("angle", "空的", "body", " "),
                "不是对象"));
        var versions = CopyPrompt.versions(output, CopyPlatform.MOMENTS);
        assertThat(versions).hasSize(1);
        var version = versions.getFirst();
        assertThat(version).containsEntry("title", "").containsEntry("body", "下雨天").containsEntry("aiScore", 5)
                .containsEntry("tags", List.of()).containsEntry("revision", 0);
    }

    @Test void tagsAreNormalisedAndDeduplicated() {
        assertThat(CopyPrompt.tags(List.of("雨天", "#雨天", " # 小店 ", "", 3), 10)).containsExactly("#雨天", "#小店");
    }

    @Test void missingVersionsFailTheBatch() {
        assertThatThrownBy(() -> CopyPrompt.versions(Map.of("text", "x"), CopyPlatform.DOUYIN))
                .isInstanceOf(NonRetryableTaskException.class);
    }

    @Test void voiceSummaryNeedsTraits() {
        assertThat(CopyPrompt.voice(Map.of("summary", "短句、口语", "traits", List.of("短句", "短句", "爱说咱家"))))
                .containsEntry("traits", List.of("短句", "爱说咱家"));
        assertThatThrownBy(() -> CopyPrompt.voice(Map.of("summary", "短句"))).isInstanceOf(NonRetryableTaskException.class);
    }

    @Test void promptListsOnlyTheAllowedStoreTokensAndAnglesToAvoid() {
        var context = new CopyPrompt.Context(CopyPlatform.DOUYIN, "幽默玩梗", "LIGHT", 2, "今天下雨", "商家写的话：今天下雨",
                List.of("店名"), null, List.of("上一条"), List.of("场景描写"));
        String prompt = CopyPrompt.prompt(context, 1, List.of("提问互动"));
        assertThat(prompt).contains("〔店名〕").doesNotContain("〔地址〕").contains("2 张图片")
                .contains("场景描写、提问互动").contains("这次写 1 条").contains("- 上一条");
    }

    @Test void storeTokensAreFilledWithTheStoreValues() {
        assertThat(CopyService.fill("—— 写于〔店名〕，〔地址〕", Map.of("店名", "梧桐小馆")))
                .isEqualTo("—— 写于梧桐小馆，〔地址〕");
    }
}
