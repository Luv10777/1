package com.wuyao.growth.live.reply;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Looks at a viewer comment before any model is asked, with rules only. A real live room is mostly
 * greetings, laughter and "666": asking the model about each of those spends the session's budget on
 * comments it would only wave through, and leaves none for the questions.
 *
 * It also decides what is never handed to the model or said on air: abuse, attempts to move viewers
 * to another platform, and text written to steer the model rather than to talk to the host.
 *
 * These rules are deliberately coarse. A comment they are unsure about is passed on, and the model
 * still judges it.
 */
@Component
public class CommentScreen {
    public enum Verdict {
        /** Nothing to respond to; decided here, the model is not asked. */
        NOISE,
        /** Not to be answered or repeated; the model is not asked. */
        BLOCKED,
        /** Reads like a question, a wish to buy or a complaint: first in line for the model. */
        PRIORITY,
        /** Anything else; the model judges it while the session's budget allows. */
        ORDINARY
    }

    /** Platform emoji arrive as text in brackets, e.g. [赞][玫瑰]. */
    private static final Pattern EMOJI_CODE = Pattern.compile("\\[[^\\[\\]\\s]{1,6}]");
    /** Everything that is not a letter or a digit in any script. */
    private static final Pattern NOT_WORDS = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern LAUGHTER = Pattern.compile("[哈呵嘿嘻嘎桀hw]{2,}|2333*");
    /** Said in passing; whole comments, compared once punctuation and emoji are gone. */
    private static final Set<String> PASSING = Set.of(
            "你好", "您好", "你好呀", "你好啊", "哈喽", "哈啰", "哈罗", "嗨", "hello", "hi", "嗨喽",
            "来了", "我来了", "来啦", "路过", "主播好", "大家好", "早", "早上好", "上午好", "中午好", "下午好", "晚上好", "晚安",
            "好", "好的", "好滴", "好吧", "好哒", "行", "嗯", "嗯嗯", "哦", "哦哦", "噢", "ok", "okk", "收到", "知道了", "明白", "了解",
            "谢谢", "多谢", "谢了", "谢谢主播", "感谢", "拜拜", "再见", "下次见",
            "哇", "哇哦", "哇塞", "赞", "棒", "真棒", "厉害", "牛", "牛啊", "不错", "可以", "支持", "加油", "沙发", "打卡", "顶");

    /** Reads like a question, a wish to buy, or something a person has to look at. */
    private static final Pattern PRIORITY_SIGNS = Pattern.compile(
            "[?？]|吗|呢|么|啥|咋|如何|多少|几[点个天块元折岁人位种]|哪|能不能|可不可以|有没有|是不是|行不行|可以"
            + "|价|钱|贵|便宜|优惠|折|券|团购|套餐|地址|位置|在哪|怎么去|营业|开门|关门|预约|预订|排队|停车|外卖|配送|包邮|发货|快递"
            + "|想买|想要|想吃|想喝|怎么买|下单|拍了|买了|来一|要一"
            + "|退款|退货|退钱|投诉|举报|过敏|拉肚子|不舒服|肚子疼|假货|假的|骗|过期|变质|发霉|异物|发票|售后|没收到|少发|漏发");

    /** A viewer taking others off the platform, or advertising to them. */
    private static final Pattern OFF_PLATFORM = Pattern.compile(
            "https?://|www\\.|\\.(?:com|cn|net|top|xyz|vip)\\b|(?<!\\d)1[3-9]\\d{9}(?!\\d)"
            + "|加.{0,4}(?:微信|v信|vx|wx|威信|薇信|卫星|企鹅|qq|扣扣)|(?:微信|vx|wx|qq)号|私聊我|私信我|兼职|刷单|代理加盟");
    /** Written at the model, not at the host. */
    private static final Pattern STEERING = Pattern.compile(
            "(?:忽略|无视|忘记|忘掉|不要管).{0,6}(?:以上|上面|之前|前面|上述|所有|一切).{0,6}(?:指令|规则|设定|提示|要求|内容)"
            + "|ignore.{0,12}(?:previous|above|all).{0,12}(?:instruction|prompt|rule)"
            + "|system\\s*prompt|系统提示|提示词|开发者模式|developer\\s*mode|越狱|jailbreak"
            + "|角色扮演|假装你是|扮演一[个位名]");
    private static final List<String> ABUSE = List.of(
            "傻逼", "傻比", "煞笔", "傻b", "沙比", "草泥马", "操你", "草你", "艹你", "他妈的", "尼玛", "去死吧", "你去死", "滚蛋",
            "贱人", "婊子", "狗日", "狗东西", "脑残", "智障", "nmsl", "cnm", "fuck", "bitch");

    private final List<String> blocked;

    /**
     * @param configured terms the operator adds: names that must not be said on air, competitors,
     *                   whatever the built-in list has no way of knowing
     */
    public CommentScreen(@Value("${growth.live.comment-screen.blocked-terms:}") List<String> configured) {
        this.blocked = java.util.stream.Stream.concat(ABUSE.stream(), configured.stream())
                .map(CommentScreen::compact).filter(term -> !term.isEmpty()).distinct().toList();
    }

    public Verdict screen(String comment) {
        String text = normalize(comment);
        if (unspeakable(text) || OFF_PLATFORM.matcher(text).find() || STEERING.matcher(text).find()) return Verdict.BLOCKED;
        String words = NOT_WORDS.matcher(EMOJI_CODE.matcher(text).replaceAll("")).replaceAll("");
        if (words.isEmpty() || DIGITS.matcher(words).matches() || LAUGHTER.matcher(words).matches()
                || PASSING.contains(words) || echoes(words)) {
            return Verdict.NOISE;
        }
        return PRIORITY_SIGNS.matcher(text).find() ? Verdict.PRIORITY : Verdict.ORDINARY;
    }

    /** One character over and over (啊啊啊), or a passing remark said twice (来了来了). */
    private static boolean echoes(String words) {
        for (int unit = 1; unit <= words.length() / 2; unit++) {
            if (words.length() % unit != 0) continue;
            String first = words.substring(0, unit);
            if ((unit == 1 || PASSING.contains(first)) && first.repeat(words.length() / unit).equals(words)) return true;
        }
        return false;
    }

    /**
     * Whether a text carries a term that is never said on air, whoever wrote it: a viewer or the model.
     * A phone number or an address is not one of them here: the store's own may be exactly what was asked for.
     */
    public boolean unspeakable(String raw) {
        String squeezed = compact(raw);
        return blocked.stream().anyMatch(squeezed::contains);
    }

    /** Full-width and half-width forms and letter case are not allowed to hide a term. */
    private static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip();
    }

    /** Nor are spaces and punctuation dropped between its characters. */
    private static String compact(String text) {
        return NOT_WORDS.matcher(normalize(text)).replaceAll("");
    }
}
