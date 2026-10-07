package com.wuyao.growth.live.script;

import com.wuyao.growth.live.LiveDtos;
import com.wuyao.growth.product.ProductDtos;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Wording handed to the text model. Kept free of I/O so it can be read and tested on its own. */
public final class ScriptPrompt {
    static final int MIN_CHARS = 90;
    static final int MAX_CHARS = 200;
    private static final int MAX_FAQS = 8;
    /** Short sentences give the player a clean place to pause, and a viewer's question a place to be answered. */
    static final int MAX_SENTENCE_CHARS = 35;
    /** How much of the previous segment is quoted so the next one can pick up from it. */
    private static final int TAIL_CHARS = 60;

    private ScriptPrompt() { }

    /** Selections this build does not know are ignored rather than passed to the model. */
    private static final Map<String, String> STYLES = Map.of(
            "亲切自然", "说话亲切自然，像和熟客聊天。",
            "热情活力", "语气热情、有感染力，节奏明快。",
            "专业沉稳", "语气沉稳专业，把依据讲清楚，不夸张。",
            "幽默轻松", "语气轻松，可以带一点幽默，但不拿商品和观众开玩笑。");
    private static final Pattern NAME = Pattern.compile("[\\p{IsHan}A-Za-z]{1,12}");

    public static String system(LiveDtos.Persona persona) {
        return system() + persona(persona);
    }

    /** Lines appended to a system prompt for the host's chosen style and name; empty when neither is usable. */
    public static String persona(LiveDtos.Persona persona) {
        StringBuilder lines = new StringBuilder();
        if (persona == null) return "";
        String style = persona.style() == null ? null : STYLES.get(persona.style());
        if (style != null) lines.append("\n主播风格：").append(style);
        // Only a plain name is ever quoted to the model; anything else is dropped.
        if (persona.name() != null && NAME.matcher(persona.name()).matches()) {
            lines.append("\n你的称呼是“").append(persona.name())
                    .append("”。打招呼或自称时可以用，不必每一段都重复。");
        }
        return lines.toString();
    }

    public static String system() {
        return """
                你是一场抖音实景直播的带货主播，正对着镜头讲解商品。请写出你接下来要说的一段口播。

                必须遵守：
                1. 只能使用【商品资料】里写明的信息。资料里没有的功效、成分、数据、价格、优惠、赠品、库存、时效和售后承诺，一律不说。
                2. 不用“最”“第一”“顶级”“国家级”“全网最低”这类绝对化用语，不做医疗或保健功效宣称，不贬低其他品牌。
                3. 像真人主播说话：口语、短句、有停顿，不念清单，不用书面腔。
                4. 只输出要说的话本身。不要标题、序号、括号说明、表情符号，也不要解释你在做什么。
                5. 数字一律写成阿拉伯数字，而且只能是资料里出现过的数字。
                6. 长度控制在 %d 到 %d 个汉字。
                7. 每句话不超过 %d 个字，用句号、问号或感叹号断句，不要一句话说到底。
                8. 观众不知道你手里有资料。不要说“资料里写了”“资料没写”这类话；没有写明的内容就不提，换一个资料里有的点来讲。
                """.formatted(MIN_CHARS, MAX_CHARS, MAX_SENTENCE_CHARS);
    }

    /**
     * @param previous the segment spoken just before this one, whatever product it was about; null for
     *                 the first segment of a session, which is the only one that opens cold
     */
    public static String user(ProductDtos.View product, ScriptPlan.Beat beat, int urgencyLevel,
                              List<String> recent, String previous, String rejection) {
        StringBuilder prompt = new StringBuilder(facts(product));
        prompt.append("\n【这一段要讲】").append(beat.instruction());
        prompt.append("\n【促单节奏】").append(ScriptPlan.urgencyInstruction(urgencyLevel));
        if (previous != null && !previous.isBlank()) {
            prompt.append("\n【上一段结尾】").append(tail(previous));
            prompt.append("\n【衔接】").append(opens(beat)
                    ? "上一段已经把前面的内容讲完了，这一段另起一个话头。用半句话自然转过来，可以顺带招呼一下刚进直播间的朋友，但不要说得像重新开播。"
                    : "这一段紧接着上一段往下讲，听起来要像同一个人没停下来。不要打招呼，不要以“家人们”“宝宝们”这类称呼开头，也不要重复上一段说过的话。");
        }
        if (!recent.isEmpty()) {
            prompt.append("\n【避免重复】下面是刚讲过的内容。这一段换一个角度和说法，不要复用其中的句子：");
            for (int i = 0; i < recent.size(); i++) prompt.append('\n').append(i + 1).append(". ").append(recent.get(i));
        }
        if (rejection != null) prompt.append("\n【上一版不合格】").append(rejection).append("。请重写，严格遵守要求。");
        return prompt.toString();
    }

    /** Only the first beat of a pass over a product starts a new topic; every other beat continues one. */
    public static boolean opens(ScriptPlan.Beat beat) {
        return beat.code().startsWith("OPENING");
    }

    private static String tail(String previous) {
        String text = previous.strip().replaceAll("\\s+", " ");
        return text.length() <= TAIL_CHARS ? text : "……" + text.substring(text.length() - TAIL_CHARS);
    }

    /** Everything the model may draw on, and therefore everything the guard checks its output against. */
    public static String facts(ProductDtos.View product) {
        StringBuilder facts = new StringBuilder("【商品资料】");
        facts.append("\n名称：").append(product.name());
        facts.append("\n类型：").append("VOUCHER".equals(product.type()) ? "团购卡券" : "实物商品");
        facts.append("\n分类：").append(product.category());
        facts.append("\n售价：").append(product.price().stripTrailingZeros().toPlainString())
                .append(" 元 / ").append(product.saleUnit());
        line(facts, "规格", product.specification());
        line(facts, "优惠", product.promotionRule());
        line(facts, "核心卖点", product.coreSellingPoints());
        List<ProductDtos.FaqView> faqs = product.faqs() == null ? List.of() : product.faqs().stream()
                .filter(faq -> "ACTIVE".equalsIgnoreCase(faq.status())).limit(MAX_FAQS).toList();
        if (!faqs.isEmpty()) {
            facts.append("\n常见问答：");
            for (ProductDtos.FaqView faq : faqs) {
                facts.append("\n- 问：").append(clip(faq.question(), 120)).append(" 答：").append(clip(faq.answer(), 300));
            }
        }
        return facts.toString();
    }

    private static void line(StringBuilder facts, String label, String value) {
        if (value != null && !value.isBlank()) facts.append('\n').append(label).append("：").append(value.strip());
    }

    private static String clip(String value, int max) {
        String text = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        return text.length() <= max ? text : text.substring(0, max);
    }
}
