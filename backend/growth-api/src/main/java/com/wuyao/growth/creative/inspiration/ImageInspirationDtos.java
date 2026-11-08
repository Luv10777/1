package com.wuyao.growth.creative.inspiration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ImageInspirationDtos {
    private ImageInspirationDtos() {}

    /** 和灵感绘图的参考图上限一致：左侧最多 6 张，方案都看。 */
    public static final int MAX_IMAGES = 6;

    /** previousId 有值表示「换一批」：同样的素材和要求，避开上一批的方向。 */
    public record Create(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9:_-]{8,80}") String requestKey,
            @NotNull @Size(max = MAX_IMAGES) List<@NotNull @Positive Long> imageAssetIds,
            @Size(max = 40) String style,
            @Size(max = 500) String idea,
            @Positive Long previousId) {}

    public record View(Long id, String status, List<Map<String, Object>> plans, String errorMessage,
                       Long previousId, Instant createdAt) {}

    /** 灵感记录里的一批方案，带上当初的素材和要求。图片已删除时 url 为空。 */
    public record History(Long id, String style, String idea, List<Image> images,
                          List<Map<String, Object>> plans, Instant createdAt) {}

    public record Image(Long assetId, String name, String url) {}

    public record Config(boolean configured, long credits, int maxImages) {}
}
