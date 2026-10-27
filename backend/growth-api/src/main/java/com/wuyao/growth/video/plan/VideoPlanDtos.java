package com.wuyao.growth.video.plan;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class VideoPlanDtos {
    private VideoPlanDtos() {}

    /** previousPlanId 有值表示「换一批」：同样的素材和要求，避开上一批的方向。 */
    public record Create(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9:_-]{8,80}") String requestKey,
            @NotNull @Size(max = 6) List<@NotNull @Positive Long> imageAssetIds,
            boolean referenceVideo,
            @Size(max = 40) String style,
            @Size(max = 500) String idea,
            @Positive Long previousPlanId) {}

    public record View(Long id, String status, List<Map<String, Object>> plans, String errorMessage,
                       Long previousPlanId, Instant createdAt) {}

    /** 灵感记录里的一批方案，带上当初的素材和要求，方便再看或换回这些参考图复用。图片已删除时 url 为空。 */
    public record History(Long id, String style, String idea, boolean referenceVideo, List<Image> images,
                         List<Map<String, Object>> plans, Instant createdAt) {}

    public record Image(Long assetId, String name, String url) {}

    public record Config(boolean configured, long credits, int maxImages) {}
}
