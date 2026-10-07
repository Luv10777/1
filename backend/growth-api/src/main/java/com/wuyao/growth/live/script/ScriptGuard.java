package com.wuyao.growth.live.script;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mechanical checks on generated narration before it is paid for again as audio and spoken to an
 * audience. They catch the failures a prompt cannot rule out — an invented price, a promise the
 * merchant never made — by comparing the text with the product facts. They are a filter for the
 * common cases, not a compliance guarantee: anything phrased without the patterns below passes.
 */
public final class ScriptGuard {
    static final int MIN_CHARS = 30;
    static final int MAX_CHARS = 400;

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    /**
     * A price said the way people say it: "12块9", "12块9毛", "12元9角5分", "12点9". Read digit by
     * digit these are a 12 and a 9, neither of which is the 12.9 the merchant wrote.
     */
    private static final Pattern SPOKEN_PRICE = Pattern.compile(
            "(?<![\\d.])(\\d+)\\s*[块元]\\s*(\\d)(?:\\s*[毛角](?:\\s*(\\d)\\s*分?)?)?(?![\\d.])");
    private static final Pattern SPOKEN_DECIMAL = Pattern.compile("(?<![\\d.])(\\d+)\\s*点\\s*(\\d{1,2})(?![\\d.])");
    /** A price or discount written in Chinese numerals would slip past the digit check. */
    private static final Pattern SPELLED_AMOUNT =
            Pattern.compile("[零一二两三四五六七八九十百千万]+(?:点[零一二三四五六七八九]+)?(?:块|元|折)");
    /** Commercial commitments: each may be spoken only if the merchant wrote it. */
    private static final List<String> CLAIMS = List.of("半价", "免单", "买一送一", "包邮", "秒杀", "限时", "限量",
            "仅剩", "假一赔", "无理由", "终身", "保修", "质保", "包退", "包换");
    private static final List<String> ABSOLUTES = List.of("全网最低", "史上最", "国家级", "顶级", "独一无二", "最便宜",
            "最低价", "销量第一", "排名第一", "全国第一", "世界第一", "根治", "治愈", "药到病除", "包治", "万能");
    private static final Pattern MARKUP = Pattern.compile("```[a-zA-Z]*|[*#`]");
    private static final Pattern LIST_MARKER = Pattern.compile("(?m)^\\s*(?:\\d+[.、)）]|[-•·])\\s*");
    private static final Pattern PICTOGRAPH =
            Pattern.compile("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]");

    private ScriptGuard() { }

    /** Strips what a speech synthesiser would read aloud as noise. Meaning is left untouched. */
    public static String clean(String raw) {
        if (raw == null) return "";
        String text = MARKUP.matcher(raw).replaceAll("");
        text = LIST_MARKER.matcher(text).replaceAll("");
        text = PICTOGRAPH.matcher(text).replaceAll("");
        text = text.replaceAll("\\s*\\R\\s*", "").replaceAll("[ \\t\\x{3000}]+", " ").strip();
        while (text.length() >= 2 && "\"“「『".indexOf(text.charAt(0)) >= 0
                && "\"”」』".indexOf(text.charAt(text.length() - 1)) >= 0) {
            text = text.substring(1, text.length() - 1).strip();
        }
        return text;
    }

    private static final Pattern LEADING_ADDRESS = Pattern.compile(
            "^(?:(?:哈喽|哈啰|嗨|来|好的?|那么?)[，,！!\\s]*)?(?:家人们|宝宝们|宝子们|宝贝们|姐妹们|朋友们|亲们|各位(?:家人|朋友|宝宝)?)[，,！!、。\\s]*");

    /**
     * Drops a greeting the model opened with although it was told to carry straight on: a segment in
     * the middle of a product must not sound like the stream starting over. Left alone when nothing
     * worth saying would remain.
     */
    public static String withoutLeadingAddress(String text) {
        String rest = LEADING_ADDRESS.matcher(text).replaceFirst("");
        return rest.length() >= MIN_CHARS ? rest : text;
    }

    /** @return why the text must not be broadcast, or empty when it passes */
    public static Optional<String> violation(String text, String facts) {
        return violation(text, facts, MIN_CHARS, MAX_CHARS);
    }

    /** The same checks for text with its own length bounds, such as a short reply to a viewer. */
    public static Optional<String> violation(String text, String facts, int minChars, int maxChars) {
        if (text.length() < minChars) return Optional.of("内容过短");
        if (text.length() > maxChars) return Optional.of("内容超过 " + maxChars + " 字");
        Set<BigDecimal> known = numbers(facts);
        Matcher number = NUMBER.matcher(withoutKnownSpokenPrices(text, known));
        while (number.find()) {
            if (!known.contains(normalize(number.group()))) {
                return Optional.of("出现了商品资料里没有的数字 " + number.group());
            }
        }
        Matcher amount = SPELLED_AMOUNT.matcher(text);
        while (amount.find()) {
            if (!facts.contains(amount.group())) return Optional.of("出现了商品资料里没有的金额或折扣“" + amount.group() + "”");
        }
        for (String claim : CLAIMS) {
            if (text.contains(claim) && !facts.contains(claim)) return Optional.of("出现了商品资料里没有的承诺“" + claim + "”");
        }
        for (String absolute : ABSOLUTES) {
            if (text.contains(absolute) && !facts.contains(absolute)) return Optional.of("使用了绝对化或功效用语“" + absolute + "”");
        }
        return Optional.empty();
    }

    /**
     * Blanks out prices said colloquially whose value the facts do contain, so that their digits
     * are not then judged one by one. One whose value is not in the facts is left as it is and
     * fails the digit check like any other invented number.
     */
    private static String withoutKnownSpokenPrices(String text, Set<BigDecimal> known) {
        StringBuilder checked = new StringBuilder(text);
        for (Pattern spoken : List.of(SPOKEN_PRICE, SPOKEN_DECIMAL)) {
            Matcher price = spoken.matcher(text);
            while (price.find()) {
                String fraction = price.group(2) + (price.groupCount() > 2 && price.group(3) != null ? price.group(3) : "");
                if (!known.contains(normalize(price.group(1) + "." + fraction))) continue;
                for (int i = price.start(); i < price.end(); i++) checked.setCharAt(i, ' ');
            }
        }
        return checked.toString();
    }

    private static Set<BigDecimal> numbers(String text) {
        Set<BigDecimal> numbers = new HashSet<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) numbers.add(normalize(matcher.group()));
        return numbers;
    }

    /** 99, 99.0 and 99.00 are the same price. */
    private static BigDecimal normalize(String number) {
        BigDecimal value = new BigDecimal(number).stripTrailingZeros();
        return value.scale() < 0 ? value.setScale(0) : value;
    }
}
