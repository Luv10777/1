package com.wuyao.growth.copywriting;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文案发出去之前的硬规则：不编数字和优惠，不用极限词，门店信息只用允许的占位符。
 *
 * 词表与直播话术的 ScriptGuard 同源，但这里不做清洗：小红书要保留分段、列表和 emoji。
 */
final class CopyGuard {
    /** 门店字段的占位符，后端按门店资料换成真实值。 */
    static final Pattern STORE_TOKEN = Pattern.compile("〔([^〔〕]{1,8})〕");
    /** 资料里没有、要商家自己补的地方。 */
    static final Pattern MISSING = Pattern.compile("【([^【】]{1,12})】");
    static final int MAX_MISSING = 3;

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern SPELLED_AMOUNT =
            Pattern.compile("[零一二两三四五六七八九十百千万]+(?:点[零一二三四五六七八九]+)?(?:块|元|折)");
    /** 小红书清单的序号不是事实，判断数字前先去掉。 */
    private static final Pattern LIST_MARKER = Pattern.compile("(?m)^\\s*(?:\\d{1,2}[.、)）]|\\d\\x{FE0F}?\\x{20E3})\\s*");
    private static final List<String> CLAIMS = List.of("半价", "免单", "买一送一", "包邮", "秒杀", "限时", "限量",
            "仅剩", "假一赔", "无理由", "终身", "保修", "质保", "包退", "包换", "免费");
    private static final List<String> ABSOLUTES = List.of("全网最低", "史上最", "国家级", "顶级", "独一无二", "最便宜",
            "最低价", "销量第一", "排名第一", "全国第一", "世界第一", "第一品牌", "根治", "治愈", "药到病除", "包治", "万能");

    private CopyGuard() {}

    /**
     * @param allowedTokens 这次允许出现的门店占位符名，例如「店名」「地址」
     * @param storeName     门店真实名称，不带店时它也不能出现
     * @param facts         商家写的一句话、所选商品和品牌资料，数字和优惠只能从这里来
     * @return 不能用的原因，通过时为空
     */
    static Optional<String> violation(CopyPlatform platform, Map<String, Object> version, Set<String> allowedTokens,
                                      String storeName, String facts) {
        String title = (String) version.getOrDefault("title", "");
        String body = (String) version.getOrDefault("body", "");
        @SuppressWarnings("unchecked") List<String> tags = (List<String>) version.getOrDefault("tags", List.of());
        if (body.isBlank()) return Optional.of("正文为空");
        if (body.length() > platform.maxBody()) return Optional.of("正文超过 " + platform.maxBody() + " 字");
        if (platform.hasTitle()) {
            if (title.isBlank()) return Optional.of("缺少标题");
            if (title.length() > platform.maxTitle()) return Optional.of("标题超过 " + platform.maxTitle() + " 字");
        } else if (!title.isBlank()) return Optional.of("朋友圈不需要标题");
        if (tags.size() > platform.maxTags()) return Optional.of("话题超过 " + platform.maxTags() + " 个");
        String all = String.join("\n", title, body, String.join(" ", tags));

        Matcher token = STORE_TOKEN.matcher(all);
        while (token.find()) {
            if (!allowedTokens.contains(token.group(1))) return Optional.of("出现了不该写的门店信息〔" + token.group(1) + "〕");
        }
        if (allowedTokens.isEmpty() && storeName != null && !storeName.isBlank() && all.contains(storeName.strip())) {
            return Optional.of("选了不提店铺，却写了店名");
        }
        int missing = 0;
        Matcher blank = MISSING.matcher(all);
        while (blank.find()) missing++;
        if (missing > MAX_MISSING) return Optional.of("待补充的地方太多");

        String checked = MISSING.matcher(STORE_TOKEN.matcher(LIST_MARKER.matcher(all).replaceAll("")).replaceAll(""))
                .replaceAll("");
        Set<BigDecimal> known = numbers(facts);
        Matcher number = NUMBER.matcher(checked);
        while (number.find()) {
            if (!known.contains(normalize(number.group()))) return Optional.of("出现了资料里没有的数字 " + number.group());
        }
        Matcher amount = SPELLED_AMOUNT.matcher(checked);
        while (amount.find()) {
            if (!facts.contains(amount.group())) return Optional.of("出现了资料里没有的金额或折扣“" + amount.group() + "”");
        }
        for (String claim : CLAIMS) {
            if (checked.contains(claim) && !facts.contains(claim)) return Optional.of("出现了资料里没有的承诺“" + claim + "”");
        }
        for (String absolute : ABSOLUTES) {
            if (checked.contains(absolute)) return Optional.of("使用了绝对化或功效用语“" + absolute + "”");
        }
        return Optional.empty();
    }

    private static Set<BigDecimal> numbers(String text) {
        Set<BigDecimal> numbers = new HashSet<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) numbers.add(normalize(matcher.group()));
        return numbers;
    }

    /** 99、99.0 和 99.00 是同一个数。 */
    private static BigDecimal normalize(String number) {
        BigDecimal value = new BigDecimal(number).stripTrailingZeros();
        return value.scale() < 0 ? value.setScale(0) : value;
    }
}
