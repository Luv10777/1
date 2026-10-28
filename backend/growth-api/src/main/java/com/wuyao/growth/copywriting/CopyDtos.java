package com.wuyao.growth.copywriting;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class CopyDtos {
    private CopyDtos() {}

    /** previousBatchId 有值表示「换一批」：同样的素材和设置，避开上一批的写法。 */
    public record Create(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9:_-]{8,80}") String requestKey,
            @NotNull @Positive Long storeId,
            @NotBlank @Pattern(regexp = "MOMENTS|DOUYIN|XIAOHONGSHU") String platform,
            @NotBlank @Size(max = 20) String style,
            @NotBlank @Pattern(regexp = "NONE|LIGHT|VISIT") String storeLevel,
            boolean voiceOn,
            @NotNull @Size(max = 6) List<@NotNull @Positive Long> imageAssetIds,
            @NotNull @Size(max = 6) List<@NotNull @Positive Long> workItemIds,
            @NotNull @Size(max = 3) List<@NotNull @Positive Long> productIds,
            @Size(max = 500) String note,
            @Positive Long previousBatchId) {}

    public record Rewrite(@NotBlank @Pattern(regexp = "SHORTER|COLLOQUIAL|NEW_OPENING|DE_AI") String action) {}

    public record Finalize(@NotNull @Positive Long batchId, @Min(0) @Max(2) int versionIndex) {}

    public record VoiceUpdate(@NotNull @Positive Long storeId,
                              @NotNull @Size(min = 3, max = 10) List<@NotBlank @Size(max = 500) String> samples) {}

    public record Style(String name, String description) {}

    public record Config(boolean configured, long credits, int maxImages, int maxProducts, int rewriteLimit, List<Style> styles) {}

    /** kind 为 ASSET（上传的图片）或 WORK（作品库）。图片已删除时 url 为空。 */
    public record Image(String kind, Long id, String name, String url) {}

    public record ProductRef(Long id, String name) {}

    /**
     * 一次生成的全部内容。文案里的〔店名〕等占位符按 store 里的值显示和复制；
     * finalized 是已经定稿进门店志的条目序号。
     */
    public record View(Long id, Long storeId, String status, String platform, String style, String storeLevel,
                       boolean voiceOn, String note, List<Image> images, List<ProductRef> products,
                       Long previousBatchId, List<Map<String, Object>> versions, List<Integer> finalized,
                       int rewriteCount, int rewriteLimit, Map<String, String> store, String errorMessage,
                       Instant createdAt) {}

    /** 创作记录里的一条。thumbnail 为空表示只写了一句话，或图片已删除。 */
    public record History(Long id, String platform, String style, String status, String firstLine, String thumbnail,
                          boolean hasImage, String creatorName, boolean finalized, String errorMessage, Instant createdAt) {}

    public record JournalEntry(Long id, Long batchId, int versionIndex, String platform, String title, String body,
                               List<String> tags, Instant finalizedAt) {}

    /**
     * 今日宜发。status：GENERATING 正在生成，READY 已生成，FAILED 生成失败，UNAVAILABLE 模型没配置。
     * fallback 为 true 时宜、忌是只按日期和行业给的稳妥建议。
     * industryStatus：KNOWN 已识别；MISSING 品牌没填行业或填了「其他」；NO_BRAND 门店没关联品牌。
     * topics 就是「宜」的那几条，每个：title、platform、style（旧数据还可能带 idea）。生成中时为空。
     */
    public record Almanac(String status, boolean fallback, String date, int day, String month, String weekday,
                          String term, String termLabel, List<String> todays, String industry, String industryStatus, Long brandId,
                          List<String> good, List<String> avoid, String quote, List<Map<String, Object>> topics,
                          String journalHint) {}

    /**
     * 口吻档案。status：EMPTY 还没喂样本，LEARNING 正在总结，READY 可用，FAILED 总结失败。
     * brandStyle 是品牌库的语言风格，还没喂样本时用它兜底。
     */
    public record Voice(String status, List<String> samples, String summary, List<String> traits, String brandStyle,
                        int remainingToday, String errorMessage, Instant updatedAt) {}
}
