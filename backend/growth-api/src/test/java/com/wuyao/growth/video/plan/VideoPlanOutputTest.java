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
