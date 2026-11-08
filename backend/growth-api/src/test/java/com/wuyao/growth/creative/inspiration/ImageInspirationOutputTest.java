package com.wuyao.growth.creative.inspiration;

import com.wuyao.growth.common.task.NonRetryableTaskException;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class ImageInspirationOutputTest {
    private static Map<String, Object> plan(String title, String prompt) {
        var plan = new LinkedHashMap<String, Object>();
        plan.put("title", title); plan.put("idea", "一句话思路");
        plan.put("materials", List.of(Map.of("index", 1, "use", "商品主体"), Map.of("index", 9, "use", "不存在的图")));
        plan.put("prompt", prompt);
        return plan;
    }

    @Test void keepsThreeValidPlansAndDropsOutOfRangeImageIndexes() {
        var output = Map.<String, Object>of("plans", List.of(plan("甲", "如图1中的商品放在晨光窗台"), plan("乙", "b"),
                plan("丙", "c"), plan("丁", "d")));
        var plans = ImageInspirationOutput.validate(output, 2);
        assertThat(plans).hasSize(3).extracting(p -> p.get("title")).containsExactly("甲", "乙", "丙");
        assertThat((List<?>) plans.getFirst().get("materials")).hasSize(1);
        assertThat(plans.getFirst()).containsOnlyKeys("title", "idea", "materials", "prompt");
    }

    @Test void aPlanWithoutAPromptDoesNotCount() {
        var output = Map.<String, Object>of("plans", List.of(plan("甲", "  "), plan("乙", "b"), plan("丙", "c")));
        assertThatThrownBy(() -> ImageInspirationOutput.validate(output, 1))
                .isInstanceOf(NonRetryableTaskException.class).hasMessageContaining("烟火已退回");
    }

    @Test void promptsLongerThanTheDescriptionBoxAreCut() {
        var output = Map.<String, Object>of("plans", List.of(plan("甲", "长".repeat(2500)), plan("乙", "b"), plan("丙", "c")));
        assertThat((String) ImageInspirationOutput.validate(output, 0).getFirst().get("prompt")).hasSize(ImageInspirationOutput.MAX_PROMPT);
    }

    @Test void missingPlansIsRejected() {
        assertThatThrownBy(() -> ImageInspirationOutput.validate(Map.of("text", "抱歉"), 1)).isInstanceOf(NonRetryableTaskException.class);
    }

    @Test void promptCarriesStyleIdeaAndThePreviousBatch() {
        String text = ImageInspirationOutput.prompt(2, "写实摄影", "新品香水上新", List.of(Map.of("title", "晨光", "idea", "清晨窗台")));
        assertThat(text).contains("2 张参考图", "绘图风格：写实摄影", "新品香水上新", "换一批", "晨光：清晨窗台");
    }

    @Test void promptWithoutImagesOrStyleSaysSo() {
        assertThat(ImageInspirationOutput.prompt(0, null, "开业海报", List.of())).contains("没有上传参考图", "未选", "开业海报")
                .doesNotContain("换一批");
    }
}
