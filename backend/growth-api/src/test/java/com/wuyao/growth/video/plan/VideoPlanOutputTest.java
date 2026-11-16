package com.wuyao.growth.video.plan;

import com.wuyao.growth.common.task.NonRetryableTaskException;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class VideoPlanOutputTest {
    private static Map<String, Object> plan(String title, int duration, List<Map<String, Object>> shots) {
        var plan = new LinkedHashMap<String, Object>();
        plan.put("title", title); plan.put("idea", "一句话思路"); plan.put("ratio", "9:16"); plan.put("durationSeconds", duration);
        plan.put("materials", List.of(Map.of("index", 1, "use", "商品主体"), Map.of("index", 9, "use", "不存在的图")));
        plan.put("shots", shots); plan.put("prompt", "如图1中的商品，9:16 竖屏 10 秒"); plan.put("negativePrompt", "画面文字");
        return plan;
    }
    private static List<Map<String, Object>> shots() {
        return List.of(Map.of("start", 0, "end", 4, "description", "特写", "images", List.of(1, 3)),
                Map.of("start", 4, "end", 10, "description", "环绕", "images", List.of()));
    }

    @Test void keepsThreeValidPlansAndDropsOutOfRangeImageIndexes() {
        var output = Map.<String, Object>of("plans", List.of(plan("甲", 10, shots()), plan("乙", 10, shots()),
                plan("丙", 10, shots()), plan("丁", 10, shots())));
        var plans = VideoPlanOutput.validate(output, 2);
        assertThat(plans).hasSize(3).extracting(p -> p.get("title")).containsExactly("甲", "乙", "丙");
        assertThat((List<?>) plans.getFirst().get("materials")).hasSize(1);
        assertThat(((List<Map<String, Object>>) plans.getFirst().get("shots")).getFirst().get("images")).isEqualTo(List.of(1));
    }

    @Test void unknownRatioFallsBackToAuto() {
        var odd = plan("甲", 10, shots()); odd.put("ratio", "2:1");
        var plans = VideoPlanOutput.validate(Map.of("plans", List.of(odd, plan("乙", 10, shots()), plan("丙", 10, shots()))), 1);
        assertThat(plans.getFirst().get("ratio")).isEqualTo("auto");
    }

    @Test void shotsThatDoNotCoverTheDurationInvalidateThePlan() {
        var gap = List.<Map<String, Object>>of(Map.of("start", 0, "end", 3, "description", "a"), Map.of("start", 5, "end", 10, "description", "b"));
        var output = Map.<String, Object>of("plans", List.of(plan("甲", 10, gap), plan("乙", 10, shots()), plan("丙", 10, shots())));
        assertThatThrownBy(() -> VideoPlanOutput.validate(output, 1))
                .isInstanceOf(NonRetryableTaskException.class).hasMessageContaining("烟火已退回");
    }

    @Test void missingPlansIsRejected() {
        assertThatThrownBy(() -> VideoPlanOutput.validate(Map.of("text", "抱歉"), 1)).isInstanceOf(NonRetryableTaskException.class);
    }

    @Test void scriptKeepsDialogueCaptionsAndSoundWithoutAddingGenericNegativeInstructions() {
        String description = "画面：店员迎接顾客，前台活动海报进入画面。".repeat(9)
                + "台词：双十一充值活动开始啦，活动持续十天！字幕：双十一充值活动·限时十天。声音：音乐渐起，切入体验画面。";
        var scriptShots = List.<Map<String, Object>>of(
                Map.of("start", 0, "end", 4, "description", description),
                Map.of("start", 4, "end", 10, "description", "顾客体验服务；旁白与字幕说明参与方式，音乐收尾。"));
        var script = plan("十日充值礼", 10, scriptShots);
        script.put("negativePrompt", "不要文字，不要人物，无字幕");
        var result = VideoPlanOutput.validate(Map.of("plans", List.of(script, script, script)), 1).getFirst();
        assertThat(((List<Map<String, Object>>) result.get("shots")).getFirst().get("description")).isEqualTo(description);
        assertThat(result.get("negativePrompt")).isEqualTo("");
    }

    @Test void twentySecondRequestRejectsFifteenSecondPlansAndConflictingPromptText() {
        var fifteenShots = List.<Map<String, Object>>of(Map.of("start", 0, "end", 5, "description", "活动开场"),
                Map.of("start", 5, "end", 15, "description", "活动内容"));
        var fifteen = plan("充值活动", 15, fifteenShots); fifteen.put("prompt", "15秒双十一活动推广视频");
        assertThatThrownBy(() -> VideoPlanOutput.validate(Map.of("plans", List.of(fifteen, fifteen, fifteen)), 1, 20, 30, "9:16"))
                .isInstanceOf(NonRetryableTaskException.class).hasMessageContaining("时长");
        var twentyShots = List.<Map<String, Object>>of(Map.of("start", 0, "end", 6, "description", "双十一活动开场"),
                Map.of("start", 6, "end", 20, "description", "十天活动内容与参与引导"));
        var twenty = plan("充值活动", 20, twentyShots); twenty.put("prompt", "20秒双十一充值推广视频，活动开展10天");
        assertThat(VideoPlanOutput.validate(Map.of("plans", List.of(twenty, twenty, twenty)), 1, 20, 30, "9:16")).hasSize(3);
        twenty.put("prompt", "15秒双十一充值推广视频");
        assertThatThrownBy(() -> VideoPlanOutput.validate(Map.of("plans", List.of(twenty, twenty, twenty)), 1, 20, 30, "9:16"))
                .isInstanceOf(NonRetryableTaskException.class).hasMessageContaining("不一致");
    }

    @Test void promptMentionsPreviousBatchForAnotherRound() {
        String text = VideoPlanOutput.prompt(2, true, "高级质感", "突出补水", List.of(Map.of("title", "晨光", "idea", "清晨桌面")), null);
        assertThat(text).contains("2 张参考图", "运镜节奏", "高级质感", "突出补水", "换一批", "晨光：清晨桌面")
                .doesNotContain("创作场景");
    }

    @Test void sceneIsAReferenceThatYieldsToTheMerchantAndStyleOnlySetsTone() {
        String text = VideoPlanOutput.prompt(1, false, "温馨治愈", "新品锅底", List.of(), "local_life");
        assertThat(text).contains("创作场景：本地生活探店", "门头", "商家的一句话需求和素材优先", "不必套同一个结构",
                "风格只管语气、节奏和画面质感");
    }

    @Test void freeOrUnknownSceneAddsNothing() {
        assertThat(VideoPlanOutput.prompt(1, false, null, "x", List.of(), "free")).doesNotContain("创作场景");
        assertThat(VideoPlanOutput.prompt(1, false, null, "x", List.of(), "no_such_scene")).doesNotContain("创作场景");
    }
}
