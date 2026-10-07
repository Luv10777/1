package com.wuyao.growth.live.script;

import com.wuyao.growth.live.LiveDtos;
import com.wuyao.growth.live.speech.LiveVoice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides what the next segment of automatic narration is about. Everything follows from the
 * segment's sequence number and the configuration frozen when the session started, so a retried
 * task lands on the same product, beat and voice.
 *
 * One pass over a product is its list of beats in order; products are then walked in their saved
 * order, forever. When co-hosts are assigned, the voice changes each time a pass over one product
 * completes, starting with the host.
 */
public final class ScriptPlan {

    public record Beat(String code, String label, String instruction) { }

    public record Slot(int seq, int round, Long productId, Beat beat, String voice) { }

    private static final Map<String, Beat> OPENINGS = table(
            new Beat("OPENING_SCENE", "场景需求引入", "用一个具体的生活或消费场景引出这件商品，让观众觉得说的就是自己。"),
            new Beat("OPENING_PRICE", "直接报价", "开门见山说出商品名称和售价，再用一句话讲它值在哪里。"),
            new Beat("OPENING_QUESTION", "悬念提问", "先抛出一个观众关心的问题，再引出这件商品作为答案。"));
    private static final Map<String, Beat> PAINS = table(
            new Beat("PAIN_SCENE", "需求场景代入", "讲清楚观众在什么情况下会需要它，把需求说具体。"),
            new Beat("PAIN_CONCERN", "顾虑问题拆解", "点出观众下单前常有的一个顾虑，并用商品资料里的事实回应。"));
    private static final Map<String, Beat> DETAILS = table(
            new Beat("DETAIL_FEATURE", "核心特点讲解", "讲核心卖点，这一段只讲一到两个，讲透。"),
            new Beat("DETAIL_PROCESS", "使用/服务流程", "讲怎么用、怎么买、到店或发货的流程；资料里没写的环节不要讲。"),
            new Beat("DETAIL_SERVICE", "售后与保障", "讲资料里写明的售后、保障和常见问题；资料没写就改为复述规格和注意事项。"));
    private static final Beat CLOSE = new Beat("CLOSE", "促单收尾", "把这件商品的价格和优惠再讲一遍，按促单节奏收尾。");
    private static final String SKIP = "不展开";

    private static final List<String> URGENCY = List.of(
            "只讲解，不催单。结尾自然带过，不要出现催促下单的话。",
            "语气平和，结尾轻轻提一句感兴趣可以看看。",
            "正常节奏，结尾用一句话引导下单或提问。",
            "节奏偏紧，突出资料里写明的优惠，引导现在下单。",
            "强促单：短句、有号召力，多次引导下单；只能用资料里写明的优惠和库存信息，不得虚构限时限量。");

    private ScriptPlan() { }

    /** Selections that this build does not know are ignored rather than passed to the model. */
    public static List<Beat> beats(LiveDtos.Config config) {
        LiveDtos.Tone tone = config == null ? null : config.tone();
        List<Beat> beats = new ArrayList<>();
        beats.add(OPENINGS.getOrDefault(tone == null ? null : tone.opening(), OPENINGS.get("场景需求引入")));
        List<String> pain = tone == null || tone.pain() == null ? List.of("需求场景代入") : tone.pain();
        if (!pain.contains(SKIP)) pain.stream().map(PAINS::get).filter(java.util.Objects::nonNull).distinct().forEach(beats::add);
        List<Beat> detail = (tone == null || tone.detail() == null ? List.<String>of() : tone.detail()).stream()
                .map(DETAILS::get).filter(java.util.Objects::nonNull).distinct().toList();
        beats.addAll(detail.isEmpty() ? List.of(DETAILS.get("核心特点讲解")) : detail);
        beats.add(CLOSE);
        return List.copyOf(beats);
    }

    /**
     * The host, followed by the co-hosts who narrate. Empty when the session has no usable host.
     * A co-host set aside to answer viewers is left out, so the answering voice is never the one
     * that was just narrating.
     */
    public static List<String> voices(LiveDtos.Config config) {
        return LiveVoice.narrators(config);
    }

    public static Slot slot(LiveDtos.Config config, List<Long> productIds, int seq) {
        List<Beat> beats = beats(config);
        List<String> voices = voices(config);
        if (productIds.isEmpty() || voices.isEmpty() || seq < 0) {
            throw new IllegalArgumentException("自动讲解需要至少一件商品和一个可用音色");
        }
        int pass = seq / beats.size();
        return new Slot(seq, pass / productIds.size(), productIds.get(pass % productIds.size()),
                beats.get(seq % beats.size()), voices.get(pass % voices.size()));
    }

    public static Beat beat(String code) {
        if (CLOSE.code().equals(code)) return CLOSE;
        for (Map<String, Beat> group : List.of(OPENINGS, PAINS, DETAILS)) {
            for (Beat beat : group.values()) if (beat.code().equals(code)) return beat;
        }
        return null;
    }

    public static int urgencyLevel(LiveDtos.Config config) {
        Double urgency = config == null ? null : config.urgency();
        return urgency == null ? 3 : (int) Math.min(5, Math.max(1, Math.round(urgency)));
    }

    public static String urgencyInstruction(int level) {
        return URGENCY.get(Math.min(5, Math.max(1, level)) - 1);
    }

    private static Map<String, Beat> table(Beat... beats) {
        Map<String, Beat> table = new LinkedHashMap<>();
        for (Beat beat : beats) table.put(beat.label(), beat);
        return table;
    }
}
