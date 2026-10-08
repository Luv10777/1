package com.wuyao.growth.live.script;

import com.wuyao.growth.product.ProductDtos;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptGuardTest {
    private static final String FACTS = ScriptPrompt.facts(new ProductDtos.View(1L, 2L, "椴树蜂蜜", "PHYSICAL", "食品",
            new BigDecimal("69.00"), "罐", "500 克", "第 2 罐立减 10 元", "东北椴树蜜，结晶细腻，无添加", "ACTIVE",
            0L, Instant.now(), Instant.now(), List.of(), List.of(
            new ProductDtos.FaqView(1L, "保质期多久", "24 个月", "ACTIVE", 0, 0L, Instant.now(), Instant.now()),
            new ProductDtos.FaqView(2L, "已下架的问题", "买一送一", "ARCHIVED", 1, 0L, Instant.now(), Instant.now()))));
    private static final String PADDING = "家人们看过来，今天给大家带来的是东北椴树蜜，结晶细腻，入口很顺。";

    @Test
    void factsCarryOnlyWhatTheMerchantSavedAndLeaveOutInactiveAnswers() {
        assertThat(FACTS).contains("售价：69 元 / 罐", "规格：500 克", "优惠：第 2 罐立减 10 元", "保质期多久", "24 个月")
                .doesNotContain("买一送一", "已下架");
    }

    @Test
    void narrationUsingOnlySavedNumbersPasses() {
        assertThat(ScriptGuard.violation(PADDING + "一罐 500 克，售价 69.0 元，第 2 罐立减 10 元，保质期 24 个月。", FACTS)).isEmpty();
    }

    @Test
    void anInventedPriceIsRejectedWhetherWrittenInDigitsOrInChineseNumerals() {
        assertThat(ScriptGuard.violation(PADDING + "原价 99，今天只要 69 元。", FACTS)).get().asString().contains("99");
        assertThat(ScriptGuard.violation(PADDING + "今天直接打五折带走。", FACTS)).get().asString().contains("五折");
        assertThat(ScriptGuard.violation(PADDING + "到手只要三十九块。", FACTS)).get().asString().contains("三十九块");
    }

    @Test
    void promisesAndSuperlativesTheMerchantNeverWroteAreRejected() {
        assertThat(ScriptGuard.violation(PADDING + "现在下单全国包邮。", FACTS)).get().asString().contains("包邮");
        assertThat(ScriptGuard.violation(PADDING + "支持七天无理由退货。", FACTS)).get().asString().contains("无理由");
        assertThat(ScriptGuard.violation(PADDING + "这是全网最低的价格。", FACTS)).get().asString().contains("全网最低");
        assertThat(ScriptGuard.violation(PADDING + "常喝能根治咳嗽。", FACTS)).get().asString().contains("根治");
    }

    @Test
    void aClaimTheMerchantDidWriteMayBeRepeated() {
        String facts = FACTS + "\n优惠：全场包邮";
        assertThat(ScriptGuard.violation(PADDING + "现在下单全场包邮。", facts)).isEmpty();
    }

    @Test
    void lengthBoundsKeepFragmentsAndRunawayOutputOffTheAir() {
        assertThat(ScriptGuard.violation("太短了", FACTS)).contains("内容过短");
        assertThat(ScriptGuard.violation("好".repeat(ScriptGuard.MAX_CHARS + 1), FACTS)).isPresent();
    }

    @Test
    void cleaningRemovesWhatASynthesiserWouldReadAsNoiseButKeepsTheWords() {
        assertThat(ScriptGuard.clean("```\n**家人们**，看这里 🎉\n1. 这款蜂蜜很细腻\n- 入口顺滑\n```"))
                .isEqualTo("家人们，看这里这款蜂蜜很细腻入口顺滑");
        assertThat(ScriptGuard.clean("“这款蜂蜜，售价 69 元。”")).isEqualTo("这款蜂蜜，售价 69 元。");
        assertThat(ScriptGuard.clean(null)).isEmpty();
    }

    @Test
    void aGreetingAtTheStartOfAContinuingSegmentIsDroppedUnlessLittleElseWouldRemain() {
        String body = "我知道很多人看到这种卡券心里会犯嘀咕，万一买多了吃不完怎么办，这个不用担心。";
        assertThat(ScriptGuard.withoutLeadingAddress("家人们，" + body)).isEqualTo(body);
        assertThat(ScriptGuard.withoutLeadingAddress("来，宝宝们！" + body)).isEqualTo(body);
        // An address in the middle of the text is ordinary speech and stays.
        assertThat(ScriptGuard.withoutLeadingAddress(body + "家人们放心拍。")).isEqualTo(body + "家人们放心拍。");
        assertThat(ScriptGuard.withoutLeadingAddress("家人们，看这里。")).isEqualTo("家人们，看这里。");
    }

    @Test
    void aPriceSaidTheWayPeopleSayItIsThePriceAndNotTwoStrayNumbers() {
        String facts = "【商品资料】\n名称：杨枝甘露兑换券\n售价：12.9 元 / 张\n核心卖点：新鲜芒果 手剥西柚";
        String around = "下午犯困的时候是不是想喝点甜的，来一张杨枝甘露兑换券，新鲜芒果配手剥西柚，";
        for (String price : List.of("只要12块9一张。", "只要 12 块 9 毛一张。", "12元9角就能喝到。", "12点9元一张。", "12.9元一张。")) {
            assertThat(ScriptGuard.violation(around + price, facts)).as(price).isEmpty();
        }
        // The same wording around a price the merchant never set is still refused.
        assertThat(ScriptGuard.violation(around + "只要12块8一张。", facts)).get().asString().contains("没有的数字 12");
        assertThat(ScriptGuard.violation(around + "只要11块9一张。", facts)).get().asString().contains("没有的数字 11");
        assertThat(ScriptGuard.violation(around + "只要12块一张。", facts)).get().asString().contains("没有的数字 12");
        // Digits that only look like a spoken price are read as what they are.
        assertThat(ScriptGuard.violation(around + "12.9元1张，买2张更划算。", facts)).get().asString().contains("没有的数字");
        String cents = "【商品资料】\n售价：9.95 元 / 张";
        assertThat(ScriptGuard.violation(around + "只要9块9毛5一张。", cents)).isEmpty();
        assertThat(ScriptGuard.violation(around + "只要9块9一张。", cents)).get().asString().contains("没有的数字 9");
    }
}
