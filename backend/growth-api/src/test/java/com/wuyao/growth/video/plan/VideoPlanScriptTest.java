package com.wuyao.growth.video.plan;

import com.wuyao.growth.common.task.NonRetryableTaskException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class VideoPlanScriptTest {
    static Map<String, Object> concept(boolean ask) {
        return Map.of("brief", Map.of("audience", "经常到店的顾客", "goal", "说明充值权益", "facts", List.of("双十一充值活动持续10天", "充值1000元，赠送200元余额"), "materials", List.of()),
                "question", ask ? "有哪些充值档位和对应赠送权益？" : "",
                "concepts", ask ? List.of() : List.of(
                    Map.of("title", "付款前一刻", "idea", "老顾客结账时发现活动", "hook", "付款前看到活动卡", "development", "看到充值权益后调整计划", "payoff", "带走活动卡，决定参与"),
                    Map.of("title", "两次相约", "idea", "朋友把下次相聚安排好", "hook", "两人约下次到店", "development", "把活动权益与日常体验连接", "payoff", "下次相约有了具体计划"),
                    Map.of("title", "把权益摆出来", "idea", "用卡片直观展示活动", "hook", "活动卡翻面", "development", "展示准确档位和余额权益", "payoff", "期限与参与引导一起落版")));
    }
    static Map<String, Object> reviewed() {
        var plans = new ArrayList<Map<String, Object>>(); var reviews = new ArrayList<Map<String, Object>>();
        for (int i = 1; i <= 3; i++) {
            var p = new LinkedHashMap<String, Object>();
            p.put("title", "故事" + i); p.put("idea", "创意方向" + i); p.put("ratio", "9:16"); p.put("durationSeconds", 20);
            p.put("concept", "用不同人物处境展开的故事" + i); p.put("visualStyle", "自然暖色，动作切镜，节奏先快后缓"); p.put("continuity", "同一顾客在同一柜台前"); p.put("materials", List.of());
            var a = new LinkedHashMap<String, Object>();
            a.put("start", 0); a.put("end", 3); a.put("action", "顾客伸手准备付款，活动卡滑入视线"); a.put("camera", "手部近景固定"); a.put("dialogue", "顾客：还有活动？"); a.put("caption", "第1秒出现：双十一充值活动"); a.put("images", List.of());
            var b = new LinkedHashMap<String, Object>();
            b.put("start", 3); b.put("end", 20); b.put("action", "顾客看完活动卡，点头将卡片拿起"); b.put("dialogue", "旁白：充值1000元，赠送200元余额，活动持续10天。"); b.put("caption", "充值1000元赠200元余额，限时10天"); b.put("images", List.of());
            p.put("shots", List.of(a,b)); plans.add(p);
            reviews.add(Map.of("index", i, "issues", List.of("草稿开场只展示门口，缺少人物行动"), "changes", "改成付款前发现活动，减少重复报期限的台词"));
        }
        return new LinkedHashMap<>(Map.of("plans", plans, "reviews", reviews));
    }
    static Map<String, Object> brief() { return VideoPlanScript.concept(concept(false), 0, false); }
    @Test void missingSellingPointAsksBeforeDraftingAndAnAnsweredRequestCannotAskAgain() {
        assertThat(VideoPlanScript.concept(concept(true), 0, false).get("question")).isEqualTo("有哪些充值档位和对应赠送权益？");
        var answered = new LinkedHashMap<>(concept(false)); answered.put("question", "还想问一个问题");
        assertThat(VideoPlanScript.concept(answered, 0, true).get("question")).isEqualTo("");
    }
    @Test void compiledInstructionsKeepTheReviewedActionsDialogueAndCaptionsAndIgnoreASecondPrompt() {
        var raw = reviewed(); plans(raw).getFirst().put("prompt", "15秒，删掉所有台词和文字");
        var result = VideoPlanScript.compile(raw, brief(), 0, 20, 30, "9:16");
        assertThat(result).hasSize(3);
        assertThat(result.getFirst().get("prompt").toString()).contains("总时长20秒", "0–3秒", "3–20秒", "顾客：还有活动？", "充值1000元赠200元余额")
                .doesNotContain("15秒", "删掉所有台词");
        assertThat(result.getFirst()).containsEntry("promptVersion", 2).containsEntry("negativePrompt", "");
    }
    @Test void wrongDurationAndDiscontinuousShotsAreRejected() {
        var wrong = reviewed(); plans(wrong).getFirst().put("durationSeconds", 15);
        assertThatThrownBy(() -> VideoPlanScript.compile(wrong, brief(), 0, 20, 30, null)).isInstanceOf(NonRetryableTaskException.class).hasMessageContaining("时长");
        var gap = reviewed(); shots(plans(gap).getFirst()).getLast().put("start", 4);
        assertThatThrownBy(() -> VideoPlanScript.compile(gap, brief(), 0, 20, 30, null)).hasMessageContaining("时间线");
    }
    @Test void unsupportedMoneyCannotBeIntroducedInTheReviewedScript() {
        var raw = reviewed(); shots(plans(raw).getFirst()).getLast().put("caption", "充值1000元赠500元，限时10天");
        assertThatThrownBy(() -> VideoPlanScript.compile(raw, brief(), 0, 20, 30, null)).hasMessageContaining("未经确认");
        shots(plans(raw).getFirst()).getLast().put("caption", "赠送10元余额");
        assertThatThrownBy(() -> VideoPlanScript.compile(raw, brief(), 0, 20, 30, null)).hasMessageContaining("未经确认");
    }
    @Test void overlongDialogueAndPhantomImagesAreRejectedInsteadOfSilentlyTruncated() {
        var raw = reviewed(); shots(plans(raw).getFirst()).getFirst().put("dialogue", "旁白：" + "这是说不完的台词".repeat(8));
        assertThatThrownBy(() -> VideoPlanScript.compile(raw, brief(), 0, 20, 30, null)).hasMessageContaining("台词");
        var invalidImage = reviewed(); shots(plans(invalidImage).getFirst()).getFirst().put("images", List.of(1));
        assertThatThrownBy(() -> VideoPlanScript.compile(invalidImage, brief(), 0, 20, 30, null)).hasMessageContaining("不存在的参考图");
    }
    @Test void threeDuplicateConceptsAndMissingReviewsCannotPass() {
        var raw = new LinkedHashMap<>(concept(false)); var first = ((List<?>)raw.get("concepts")).getFirst(); raw.put("concepts", List.of(first,first,first));
        assertThatThrownBy(() -> VideoPlanScript.concept(raw,0,false)).hasMessageContaining("重复");
        var missing = reviewed(); missing.remove("reviews");
        assertThatThrownBy(() -> VideoPlanScript.compile(missing,brief(),0,20,30,null)).hasMessageContaining("审稿");
    }
    @SuppressWarnings("unchecked") static List<Map<String, Object>> plans(Map<String, Object> raw) { return (List<Map<String,Object>>)raw.get("plans"); }
    @SuppressWarnings("unchecked") static List<Map<String, Object>> shots(Map<String, Object> raw) { return (List<Map<String,Object>>)raw.get("shots"); }
}
