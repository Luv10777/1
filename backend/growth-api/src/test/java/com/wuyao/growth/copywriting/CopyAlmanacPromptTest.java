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

    private static Map<String, Object> good(String text, String platform, String style) {
        return Map.of("text", text, "platform", platform, "style", style);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> topics(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("topics");
    }

    @Test void industriesFollowTheBrandLibraryOptions() {
        assertThat(CopyAlmanacPrompt.industryKey("餐饮 / 茶饮")).isEqualTo("餐饮 / 茶饮");
        assertThat(CopyAlmanacPrompt.industryKey("其他")).isEqualTo(CopyAlmanacPrompt.GENERAL);
        assertThat(CopyAlmanacPrompt.industryKey("火锅")).isEqualTo(CopyAlmanacPrompt.GENERAL);
        assertThat(CopyAlmanacPrompt.industryKey(null)).isEqualTo(CopyAlmanacPrompt.GENERAL);
    }

    @Test void whatIsGoodToPostIsWhatCanBePostedToday() {
        var output = Map.<String, Object>of("good", List.of(good("晒一杯暖茶", "MOMENTS", "文艺治愈"), good("讲一桌家常味", "XIAOHONGSHU", "真诚日常"),
                        good("拍后厨备餐", "DOUYIN", "不存在的风格")),
                "avoid", List.of("硬广刷屏"), "quote", "霜降渐深，热茶正好", "journalHint", "最近没写老板自己了");
        var result = CopyAlmanacPrompt.normalize(output, ORDINARY, "餐饮 / 茶饮");
        assertThat(result.get("good")).isEqualTo(List.of("晒一杯暖茶", "讲一桌家常味", "拍后厨备餐"));
        assertThat(topics(result)).extracting(t -> t.get("title")).containsExactly("晒一杯暖茶", "讲一桌家常味", "拍后厨备餐");
        assertThat(topics(result)).extracting(t -> t.get("platform")).containsExactly("MOMENTS", "XIAOHONGSHU", "DOUYIN");
        assertThat(topics(result).get(2)).containsEntry("style", "真诚日常");
        assertThat(result.get("avoid")).isEqualTo(List.of("硬广刷屏", "暗示保健功效"));
        assertThat(result.get("journalHint")).isEqualTo("最近没写老板自己了");
    }

    @Test void inventedNumbersAbsolutesAndUnknownPlatformsAreDropped() {
        var output = Map.<String, Object>of("good", List.of(good("第二杯 9 块", "MOMENTS", "真诚日常"), good("全网最低价", "MOMENTS", "热情福利"),
                        good("热汤上桌", "WEIBO", "真诚日常"), good("晒一碗热汤", "MOMENTS", "文艺治愈"), good("讲老顾客的故事", "DOUYIN", "幽默玩梗")),
                "avoid", List.of("硬广刷屏"));
        var result = CopyAlmanacPrompt.normalize(output, ORDINARY, CopyAlmanacPrompt.GENERAL);
        assertThat(result.get("good")).isEqualTo(List.of("晒一碗热汤", "讲老顾客的故事"));
        assertThat(result.get("avoid")).isEqualTo(List.of("硬广刷屏"));
    }

    @Test void solemnDaysNeverPromote() {
        var output = Map.<String, Object>of("good", List.of(good("促销活动", "DOUYIN", "热情福利"), good("安静地开门", "MOMENTS", "热情福利"),
                        good("给老顾客的问候", "XIAOHONGSHU", "真诚日常")),
                "avoid", List.of("刷屏"));
        var result = CopyAlmanacPrompt.normalize(output, QINGMING, CopyAlmanacPrompt.GENERAL);
        assertThat(result.get("good")).isEqualTo(List.of("安静地开门", "给老顾客的问候"));
        assertThat(result.get("avoid")).isEqualTo(List.of("喜庆促销", "刷屏"));
        assertThat(topics(result)).extracting(t -> t.get("style")).containsOnly("真诚日常");
    }

    @Test void tooLittleUsableContentFails() {
        assertThatThrownBy(() -> CopyAlmanacPrompt.normalize(Map.of("good", List.of(good("晒一碗热汤", "MOMENTS", "真诚日常")),
                "avoid", List.of("刷屏")), ORDINARY, CopyAlmanacPrompt.GENERAL)).isInstanceOf(NonRetryableTaskException.class);
        // 旧格式（宜是纯文字）没有平台，不能点去写，同样不算数。
        assertThatThrownBy(() -> CopyAlmanacPrompt.normalize(Map.of("good", List.of("晒一碗热汤", "讲老顾客的故事"),
                "avoid", List.of("刷屏")), ORDINARY, CopyAlmanacPrompt.GENERAL)).isInstanceOf(NonRetryableTaskException.class);
    }

    @Test void theFallbackCanStillBeWrittenFrom() {
        var fallback = CopyAlmanacPrompt.fallback(QINGMING, "美妆 / 个护");
        assertThat(fallback.get("avoid")).isEqualTo(List.of("喜庆促销", "写功效和前后对比"));
        assertThat(topics(fallback)).extracting(t -> t.get("title")).containsExactlyElementsOf((List<?>) fallback.get("good"));
    }

    @Test void thePromptCarriesDateFactsIndustryAndRecentPosts() {
        String prompt = CopyAlmanacPrompt.prompt(ORDINARY, "餐饮 / 茶饮", "梧桐小馆", List.of("招牌牛肉面"), List.of("10-25 朋友圈：新菜上桌"));
        assertThat(prompt).contains("霜降").contains("星期三").contains("餐饮 / 茶饮").contains("招牌牛肉面").contains("新菜上桌");
    }
}
