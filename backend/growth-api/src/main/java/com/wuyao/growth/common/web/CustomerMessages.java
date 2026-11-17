package com.wuyao.growth.common.web;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 给客户看的失败原因：模型供应商、中转站返回的原始报错（HTTP 状态码、JSON、英文错误码）
 * 换成商家看得懂的中文。原始报错仍存在数据库和日志里供排查，只在接口返回给客户时转换。
 * 已经是平台自己写的中文提示，原样返回。
 */
public final class CustomerMessages {

    private record Rule(Pattern pattern, String message) {
        Rule(String regex, String message) { this(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), message); }
    }

    /** 原始报错的痕迹：JSON 符号、HTTP、英文错误码、内部称呼。 */
    private static final Pattern TECHNICAL = Pattern.compile(
            "[{}\"\\\\]|HTTP|Exception|供应商|中转站|上游|[A-Z][a-z]+[A-Z]\\w*|[a-z]+_[a-z]+");

    // 顺序有讲究：真人隐私的错误码里也带 Sensitive，要排在敏感内容前面。
    private static final List<Rule> RULES = List.of(
            new Rule("PrivacyInformation|真人|人脸|\\bface\\b|portrait",
                    "参考图里有清晰的真人面孔，出于隐私保护这个模型不能直接使用。换成不含真人正脸的图片再试试。"),
            new Rule("Sensitive|敏感|审核|违规|\\brisk\\b|content.?policy|moderation",
                    "画面或描述里可能有平台不允许的内容，没有通过审核。换个说法或换张参考图再试试。"),
            new Rule("\\bratio\\b|比例|画幅", "这个模型不支持当前的画面比例，换一个比例再试试。"),
            new Rule("\\bduration\\b|\\bseconds\\b|时长不", "这个模型不支持当前的视频时长，调整一下时长再试试。"),
            new Rule("\\bresolution\\b|分辨率", "这个模型不支持当前的清晰度，换一个清晰度再试试。"),
            new Rule("image.{0,20}(size|dimension|pixel|width|height)|尺寸|像素|宽高|too (large|small)",
                    "参考图的尺寸或大小不符合要求，换一张清晰一些、不要太大的图片再试试。"),
            new Rule("中转站记录", "这次生成的结果还在确认中，建议过几分钟再看看；如果直接重新生成，可能会再扣一次烟火。"),
            new Rule("\\b429\\b|rate.?limit|too many|限流|繁忙|\\bbusy\\b|overload|concurren|并发",
                    "现在使用的人比较多，请过几分钟再试。"),
            new Rule("timeout|timed out|超时|规定时间|处理时长", "这次生成等了太久没有完成，请稍后重试。"),
            new Rule("quota|insufficient|balance|余额|额度|\\b40[13]\\b|unauthori|forbidden|api.?key",
                    "生成服务暂时出了点问题，我们正在处理，请稍后再试。"),
            new Rule("\\b5\\d\\d\\b|internal|server error", "生成服务暂时不稳定，请稍后重试。"));

    private CustomerMessages() {}

    /**
     * @param raw      存下来的失败原因，可能是原始报错
     * @param fallback 认不出是什么问题时给客户的话，例如「视频没有生成成功，请稍后重试。」
     */
    public static String plain(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return raw;
        if (!TECHNICAL.matcher(raw).find()) return raw;
        for (Rule rule : RULES) if (rule.pattern().matcher(raw).find()) return rule.message();
        return fallback;
    }
}
