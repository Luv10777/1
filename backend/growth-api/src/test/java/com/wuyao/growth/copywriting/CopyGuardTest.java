package com.wuyao.growth.copywriting;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CopyGuardTest {
    private static Map<String, Object> moments(String body) {
        return Map.of("title", "", "body", body, "tags", List.of());
    }

    private static Map<String, Object> xhs(String title, String body, List<String> tags) {
        return Map.of("title", title, "body", body, "tags", tags);
    }

    @Test void plainCopyWithAllowedStoreTokenPasses() {
        var result = CopyGuard.violation(CopyPlatform.MOMENTS, moments("下雨天，店里难得安静。\n—— 写于〔店名〕"),
                Set.of("店名"), "梧桐小馆", "");
        assertThat(result).isEmpty();
    }

    @Test void numbersMustComeFromTheFacts() {
        assertThat(CopyGuard.violation(CopyPlatform.MOMENTS, moments("今天第二杯 9.9 元"), Set.of(), "店", ""))
                .hasValueSatisfying(reason -> assertThat(reason).contains("9.9"));
        assertThat(CopyGuard.violation(CopyPlatform.MOMENTS, moments("今天第二杯 9.90 元"), Set.of(), "店", "商品「拿铁」：价格 9.9 元"))
                .isEmpty();
    }

    @Test void listNumbersAndPlaceholdersAreNotTreatedAsFacts() {
        var version = xhs("下雨天去哪儿", "1. 人少，不用排队\n2. 开店第【几】年了\n3. 窗边的位子", List.of("#探店"));
        assertThat(CopyGuard.violation(CopyPlatform.XIAOHONGSHU, version, Set.of(), "梧桐小馆", "")).isEmpty();
    }

    @Test void promisesAndAbsolutesAreRejected() {
        assertThat(CopyGuard.violation(CopyPlatform.MOMENTS, moments("今天全场包邮"), Set.of(), "店", ""))
                .hasValueSatisfying(reason -> assertThat(reason).contains("包邮"));
        assertThat(CopyGuard.violation(CopyPlatform.MOMENTS, moments("今天全场包邮"), Set.of(), "店", "优惠 满两件包邮")).isEmpty();
        assertThat(CopyGuard.violation(CopyPlatform.MOMENTS, moments("全网最低的一碗面"), Set.of(), "店", "全网最低"))
                .hasValueSatisfying(reason -> assertThat(reason).contains("绝对化"));
        assertThat(CopyGuard.violation(CopyPlatform.MOMENTS, moments("今天打八折"), Set.of(), "店", ""))
                .hasValueSatisfying(reason -> assertThat(reason).contains("八折"));
    }

    @Test void storeInformationFollowsTheChosenLevel() {
        assertThat(CopyGuard.violation(CopyPlatform.MOMENTS, moments("欢迎来〔地址〕坐坐"), Set.of("店名"), "梧桐小馆", ""))
                .hasValueSatisfying(reason -> assertThat(reason).contains("地址"));
        assertThat(CopyGuard.violation(CopyPlatform.MOMENTS, moments("梧桐小馆今天下雨"), Set.of(), "梧桐小馆", ""))
                .hasValueSatisfying(reason -> assertThat(reason).contains("店名"));
    }

    @Test void platformShapesAreEnforced() {
        assertThat(CopyGuard.violation(CopyPlatform.DOUYIN, xhs("", "正文", List.of("#a")), Set.of(), "店", ""))
                .hasValue("缺少标题");
        assertThat(CopyGuard.violation(CopyPlatform.XIAOHONGSHU, xhs("这是一个非常非常非常非常长的小红书标题啊呀", "正文", List.of()), Set.of(), "店", ""))
                .hasValueSatisfying(reason -> assertThat(reason).contains("标题超过"));
        assertThat(CopyGuard.violation(CopyPlatform.MOMENTS, xhs("标题", "正文", List.of()), Set.of(), "店", ""))
                .hasValue("朋友圈不需要标题");
        assertThat(CopyGuard.violation(CopyPlatform.MOMENTS, moments("【一】【二】【三】【四】"), Set.of(), "店", ""))
                .hasValue("待补充的地方太多");
    }
}
