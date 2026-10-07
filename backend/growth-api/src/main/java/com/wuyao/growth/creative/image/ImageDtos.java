package com.wuyao.growth.creative.image;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ImageDtos {
    private ImageDtos() {}
    public record Reference(@NotNull @Positive Long assetId,
                            @NotNull @Pattern(regexp = "SUBJECT|STYLE|LAYOUT|BACKGROUND") String role) {}
    public record Create(
        @NotBlank @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String requestKey,
        @NotNull @Pattern(regexp = "POSTER|PRODUCT_SET") String workflow,
        @NotNull @Size(max = 2000) String brief,
        @NotNull @Size(max = 6) List<@Valid Reference> references,
        @NotNull @Pattern(regexp = "1:1|3:4|4:3|9:16|16:9|2:3|3:2") String ratio,
        @Pattern(regexp = "1K|2K|4K") String quality,
        @Min(1) @Max(6) int count,
        @NotNull @Pattern(regexp = "TAOBAO|JD|PDD|XIAOHONGSHU|MEITUAN|TAOBAO_FLASH|DOUYIN_GROUP|DIANPING|WECHAT|DOUYIN|ELEME|OFFLINE|LOCAL") String platform,
        @NotNull @Pattern(regexp = "PRODUCT_MAIN|WHITE_BG|DETAIL|SCENE_SET|DISH|STORE|POSTER") String imageType,
        @NotNull @Size(max = 80) String purpose,
        @NotNull @Size(max = 80) String style,
        @Size(max = 40) String templateId) {}
    public record Revision(@NotBlank @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String requestKey,
                           @NotBlank @Size(max = 1000) String instruction,
                           @Pattern(regexp = "LAYOUT|SCENE|MESSAGE") String variation) {
        public Revision(String requestKey, String instruction) { this(requestKey, instruction, null); }
    }
    public record TextEdit(@NotBlank @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String requestKey,
                           @NotNull @Size(max = 40) String headline,
                           @NotNull @Size(max = 100) String caption) {}
    public record Rename(@NotBlank @Size(max = 100) String title) {}
    public record ItemView(Long id, int ordinal, String role, String headline, String caption,
                           String status, String error, String url, int width, int height, Long taskId,
                           boolean similarityWarning, String providerImageUrl, boolean persisted,
                           boolean downloadFailed) {}
    public record View(Long id, Long parentId, String workflow, String brief, String quality,
                       String ratio, String status, String summary, String question, String error,
                       int completed, int count, List<ItemView> items, Instant createdAt, Long taskId) {}
    public record History(Long id, String workflow, String title, String brief, String quality, Instant createdAt,
                          String status, int completed, int count, String previewUrl) {}
    public record Work(Long id, Long creationId, String workflow, String title,
                       String url, String quality, int width, int height, Instant createdAt) {}
    public record Dimensions(int width, int height) {}
    public record Plan(String summary, String question, String visualDirection, List<Spec> items, PlanningTrace planning) {
        public Plan(String summary, String question, String visualDirection, List<Spec> items) {
            this(summary, question, visualDirection, items, null);
        }
        public Plan(String summary, String question, List<Spec> items) {
            this(summary, question, summary, items);
        }
    }
    public record PlanningTrace(String alias, String model, String version, Map<String,Object> usage) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Spec(String role, String prompt, String headline, String caption, Long editSourceItemId,
                       String shotType, String focalPoint, String materialLanguage,
                       String cameraLanguage, List<String> mustPreserve, List<String> mustAvoid) {
        public Spec(String role, String prompt, String headline, String caption) {
            this(role, prompt, headline, caption, null, null, null, null, null, List.of(), List.of());
        }
        public Spec(String role, String prompt, String headline, String caption, Long editSourceItemId) {
            this(role, prompt, headline, caption, editSourceItemId, null, null, null, null, List.of(), List.of());
        }
        public Spec(String role, String prompt, String headline, String caption, String shotType, String focalPoint,
                    String materialLanguage, String cameraLanguage, List<String> mustPreserve, List<String> mustAvoid) {
            this(role, prompt, headline, caption, null, shotType, focalPoint, materialLanguage,
                cameraLanguage, mustPreserve == null ? List.of() : mustPreserve,
                mustAvoid == null ? List.of() : mustAvoid);
        }
    }
}
