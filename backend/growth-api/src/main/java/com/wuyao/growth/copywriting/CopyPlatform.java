package com.wuyao.growth.copywriting;

/** 三个平台的写法和长度上限。上限比提示词里要求的略宽，留一点余量给模型。 */
enum CopyPlatform {
    MOMENTS("朋友圈", false, 0, 300, 0,
            "朋友圈：2 到 5 行短句，像熟人聊天；不要标题，不要话题标签；emoji 最多 2 个，没必要就不用。"),
    DOUYIN("抖音", true, 30, 300, 6,
            "抖音：title 是一句不超过 20 字的钩子标题，前几个字最要紧；body 1 到 3 句，不超过 100 字；tags 3 到 5 个话题，每个以 # 开头、不超过 10 字。"),
    XIAOHONGSHU("小红书", true, 20, 1000, 10,
            "小红书：title 不超过 18 字，可以用「｜」分隔主题，可带 1 个 emoji；body 3 到 6 段，段与段之间空一行，可以用清单，不超过 500 字；tags 3 到 8 个，每个以 # 开头、不超过 10 字。");

    private final String label;
    private final boolean hasTitle;
    private final int maxTitle;
    private final int maxBody;
    private final int maxTags;
    private final String rule;

    CopyPlatform(String label, boolean hasTitle, int maxTitle, int maxBody, int maxTags, String rule) {
        this.label = label;
        this.hasTitle = hasTitle;
        this.maxTitle = maxTitle;
        this.maxBody = maxBody;
        this.maxTags = maxTags;
        this.rule = rule;
    }

    String label() { return label; }
    boolean hasTitle() { return hasTitle; }
    int maxTitle() { return maxTitle; }
    int maxBody() { return maxBody; }
    int maxTags() { return maxTags; }
    String rule() { return rule; }
}
