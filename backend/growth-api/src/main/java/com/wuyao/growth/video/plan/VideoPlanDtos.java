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

    /** 和视频生成的参考图上限（VideoDtos.referenceImageAssetIds）保持一致：左侧放多少张，方案就看多少张。 */
    public static final int MAX_IMAGES = 30;

    /** previousPlanId 有值表示「换一批」：同样的素材和要求，避开上一批的方向。 */
    public record Create(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9:_-]{8,80}") String requestKey,
            @NotNull @Size(max = MAX_IMAGES) List<@NotNull @Positive Long> imageAssetIds,
            boolean referenceVideo,
            @Size(max = 40) String style,
            @Size(max = 500) String idea,
            @Positive Long previousPlanId,
            // 工作台选的创作场景；「换一批」时服务端改用上一批的场景。
            @Pattern(regexp = "[a-z_]{1,40}") String sceneKey) {
        public Create(String requestKey, List<Long> imageAssetIds, boolean referenceVideo, String style, String idea, Long previousPlanId) {
            this(requestKey, imageAssetIds, referenceVideo, style, idea, previousPlanId, null);
        }
    }

    public record View(Long id, String status, List<Map<String, Object>> plans, String errorMessage,
                       Long previousPlanId, Instant createdAt, String sceneKey) {}

    /** 灵感记录里的一批方案，带上当初的素材和要求，方便再看或换回这些参考图复用。图片已删除时 url 为空。 */
    public record History(Long id, String style, String idea, boolean referenceVideo, List<Image> images,
                         List<Map<String, Object>> plans, Instant createdAt, String sceneKey) {}

    public record Image(Long assetId, String name, String url) {}

    public record Config(boolean configured, long credits, int maxImages) {}
}
