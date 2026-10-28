package com.wuyao.growth.copywriting;

import com.wuyao.growth.common.task.NonRetryableTaskException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class CopyAlmanacPromptTest {
    private static final CopyCalendar.Facts ORDINARY = CopyCalendar.of(LocalDate.of(2026, 10, 28));
    private static final CopyCalendar.Facts QINGMING = CopyCalendar.of(LocalDate.of(2026, 4, 5));

    private static Map<String, Object> topic(String title, String platform, String style) {
        return Map.of("title", title, "platform", platform, "style", style, "idea", title + "，写写店里的样子");
    }

    @Test void industriesFollowTheBrandLibraryOptions() {
        assertThat(CopyAlmanacPrompt.industryKey("餐饮 / 茶饮")).isEqualTo("餐饮 / 茶饮");
        assertThat(CopyAlmanacPrompt.industryKey("其他")).isEqualTo(CopyAlmanacPrompt.GENERAL);
        assertThat(CopyAlmanacPrompt.industryKey("火锅")).isEqualTo(CopyAlmanacPrompt.GENERAL);
        assertThat(CopyAlmanacPrompt.industryKey(null)).isEqualTo(CopyAlmanacPrompt.GENERAL);
    }

    @Test void theIndustryComplianceReminderIsAlwaysInAvoid() {
        var output = Map.<String, Object>of("good", List.of("晒一碗热汤", "讲老顾客的故事"), "avoid", List.of("硬广刷屏"),
                "quote", "天一冷，人就想找个暖和的地方坐坐。", "journalHint", "最近没写老板自己了",
                "topics", List.of(topic("今天的第一碗热汤", "MOMENTS", "文艺治愈"), topic("老顾客的一句话", "XIAOHONGSHU", "真诚日常"),
                        topic("降温了你想吃什么", "DOUYIN", "不存在的风格")));
        var result = CopyAlmanacPrompt.normalize(output, ORDINARY, "餐饮 / 茶饮");
        assertThat(result.get("avoid")).isEqualTo(List.of("硬广刷屏", "暗示保健功效"));
        assertThat(result.get("journalHint")).isEqualTo("最近没写老板自己了");
        @SuppressWarnings("unchecked") var topics = (List<Map<String, Object>>) result.get("topics");
        assertThat(topics).hasSize(3);
        assertThat(topics.get(2)).containsEntry("style", "真诚日常");
    }

    @Test void inventedNumbersAbsolutesAndUnknownPlatformsAreDropped() {
        var output = Map.<String, Object>of("good", List.of("第二杯 9 块", "晒一碗热汤", "全网最低价"), "avoid", List.of("硬广刷屏"),
                "topics", List.of(topic("开店 3 周年", "MOMENTS", "真诚日常"), topic("热汤上桌", "WEIBO", "真诚日常"),
                        topic("今天的第一碗热汤", "MOMENTS", "文艺治愈"), topic("老顾客的一句话", "DOUYIN", "幽默玩梗")));
        var result = CopyAlmanacPrompt.normalize(output, ORDINARY, CopyAlmanacPrompt.GENERAL);
        assertThat(result.get("good")).isEqualTo(List.of("晒一碗热汤"));
        assertThat(result.get("avoid")).isEqualTo(List.of("硬广刷屏"));
        @SuppressWarnings("unchecked") var topics = (List<Map<String, Object>>) result.get("topics");
        assertThat(topics).extracting(t -> t.get("title")).containsExactly("今天的第一碗热汤", "老顾客的一句话");
    }

    @Test void solemnDaysNeverPromote() {
        var output = Map.<String, Object>of("good", List.of("促销活动", "安静地开门"), "avoid", List.of("刷屏"),
                "topics", List.of(topic("清明特价", "DOUYIN", "热情福利"), topic("雨中的小店", "MOMENTS", "热情福利"),
                        topic("给老顾客的问候", "XIAOHONGSHU", "真诚日常")));
        var result = CopyAlmanacPrompt.normalize(output, QINGMING, CopyAlmanacPrompt.GENERAL);
        assertThat(result.get("good")).isEqualTo(List.of("安静地开门"));
        assertThat(result.get("avoid")).isEqualTo(List.of("喜庆促销", "刷屏"));
        @SuppressWarnings("unchecked") var topics = (List<Map<String, Object>>) result.get("topics");
        assertThat(topics).extracting(t -> t.get("title")).containsExactly("雨中的小店", "给老顾客的问候");
        assertThat(topics).extracting(t -> t.get("style")).containsOnly("真诚日常");
    }

    @Test void tooLittleUsableContentFails() {
        assertThatThrownBy(() -> CopyAlmanacPrompt.normalize(Map.of("good", List.of("晒一碗热汤"), "avoid", List.of("刷屏"),
                "topics", List.of(topic("一个选题", "MOMENTS", "真诚日常"))), ORDINARY, CopyAlmanacPrompt.GENERAL))
                .isInstanceOf(NonRetryableTaskException.class);
    }

    @Test void thePromptCarriesDateFactsIndustryAndRecentPosts() {
        String prompt = CopyAlmanacPrompt.prompt(ORDINARY, "餐饮 / 茶饮", "梧桐小馆", List.of("招牌牛肉面"), List.of("10-25 朋友圈：新菜上桌"));
        assertThat(prompt).contains("霜降").contains("星期三").contains("餐饮 / 茶饮").contains("招牌牛肉面").contains("新菜上桌");
    }
}
