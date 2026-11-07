package com.wuyao.growth.video;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * 创作场景。一期内置在代码里（和图片侧 PosterStyles 同一做法），设计见 docs/video-scenes.md。
 * 分镜参考只在服务端用，不下发前端；它只补用户没写到的部分，冲突时以用户的描述为准。
 */
@Slf4j
public final class VideoScenes {
    public static final String FREE = "free";

    record Scene(String key, String name, String summary, String icon, String placeholder, String materialHint, String guide) {}

    /** 前端场景面板用的字段，不含分镜参考。 */
    public record View(String key, String name, String summary, String icon, String placeholder, String materialHint) {}

    private static final List<Scene> ALL = List.of(
            new Scene(FREE, "自由创作", "不套场景，完全按你的描述生成", "edit_note", null, null, null),
            new Scene("ecommerce", "电商带货", "商品主图视频、带货短视频", "shopping_bag",
                    "例如：这款保温杯 12 小时保温，今天下单立减 20 元",
                    "放一张商品图效果更好，多角度的商品图能让外观更稳定",
                    "开头 3 秒内给商品特写，点出最核心的一个卖点；中段展示使用场景和细节质感；"
                            + "结尾用一个干净的商品定格镜头，留出放价格和优惠字幕的位置。节奏紧凑，商品外观以参考图为准。"),
            new Scene("local_life", "本地生活探店", "餐饮、丽人、休闲门店引流", "storefront",
                    "例如：新品鸳鸯锅底，午市 8 折，适合朋友聚餐",
                    "放门头或店内照片，再加一张招牌产品照",
                    "以到店视角展开：先给门头或店内环境的入场镜头，再给招牌产品或服务的近景特写，"
                            + "接着拍顾客享用或体验时的真实反应，结尾用一个有氛围的全景收住，留出放地址和到店理由字幕的位置。画面要有现场感和烟火气。"),
            new Scene("event", "活动引流", "节日、新品、限时优惠", "celebration",
                    "例如：中秋限定礼盒，9 月 1 日到 15 日买二送一",
                    "写清楚活动内容和时间，效果更好",
                    "开头用一个醒目的画面点明活动主题（节日、新品或限时），中段展示参与活动的产品或热闹场面，"
                            + "结尾留出放优惠内容和活动时间字幕的位置。节奏明快。"),
            new Scene("brand", "品牌宣传", "品牌故事、门店形象片", "verified",
                    "例如：二十年老字号，每天凌晨四点现熬骨汤",
                    "放品牌标志、门店或工艺过程的照片",
                    "从情绪或场景引入，不急着露出产品；中段表现品牌的理念、工艺或用心的细节；"
                            + "结尾落在有品牌辨识度的画面上，留出放品牌名和一句口号的位置。镜头稳、节奏舒展，质感优先。"),
            new Scene("drama", "剧情短片", "15–30 秒的小剧情、反转种草", "theaters",
                    "例如：女生和闺蜜吵架，最后一杯奶茶让两人和好",
                    "写清楚角色和一句话剧情；需要固定人物长相时放人物参考图",
                    "按小剧情来拍：开场用冲突或悬念抓住注意力，中段推进情节，结尾给出反转或答案，"
                            + "商品或门店在情节里自然出现，不硬广。可以出现人物，人物外观以参考图为准；对白写成简短口语。"));

    private VideoScenes() {}

    public static List<View> views() {
        return ALL.stream().map(s -> new View(s.key(), s.name(), s.summary(), s.icon(), s.placeholder(), s.materialHint())).toList();
    }

    /** 有分镜参考的场景；空、自由创作和未知的 key 都返回空，未知的记日志。 */
    static Optional<Scene> guided(String key) {
        if (key == null || key.isBlank() || FREE.equals(key)) return Optional.empty();
        var scene = ALL.stream().filter(s -> s.key().equals(key)).findFirst();
        if (scene.isEmpty()) log.warn("Unknown video scene key {}, treated as free creation", key);
        return scene;
    }

    /** 存进记录的场景：只留有分镜参考的 key，其余记为空（即自由创作）。 */
    public static String normalize(String key) {
        return guided(key).map(Scene::key).orElse(null);
    }

    /** 方案模型看到的场景说明；没有场景时返回空。 */
    public static Optional<String> planBrief(String key) {
        return guided(key).map(s -> "创作场景：" + s.name() + "（商家选的场景也属于商家的需求）。这类视频常见的分镜思路：" + s.guide()
                + "\n这只是参考：商家的一句话需求和素材优先，三个方案都要贴合这个场景，但不必套同一个结构。");
    }

    /**
     * 交给视频模型的提示词：用户原文在前、一字不改，场景参考附在后面。
     * 自由创作、未知场景、或提示词来自已采纳的方案（方案已经参考过场景）时原样返回。
     */
    public static String providerPrompt(VideoDtos.Create request) {
        String prompt = request.prompt();
        if (request.planId() != null) return prompt;
        return guided(request.sceneKey()).map(s -> prompt.isBlank()
                ? "【创作场景：" + s.name() + "】请按参考素材来拍，这类视频常见的分镜思路：" + s.guide()
                : prompt + "\n\n【创作场景：" + s.name() + "】以下是这类视频常见的分镜思路，只用来补充上面没写到的部分；"
                        + "和上面的描述冲突时，一律以上面的描述为准：" + s.guide()).orElse(prompt);
    }
}
