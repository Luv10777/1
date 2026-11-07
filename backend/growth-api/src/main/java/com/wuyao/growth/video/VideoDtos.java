package com.wuyao.growth.video;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class VideoDtos {
    private VideoDtos() {}

    public record Create(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String requestKey,
            @NotBlank String prompt,
            @NotNull @Size(max = 30) List<@Positive Long> referenceImageAssetIds,
            @Positive Long referenceVideoAssetId,
            @NotBlank @Pattern(regexp = "SEEDANCE_2_5|SEEDANCE_2_5_SPECIAL|SEEDANCE_2_0|SEEDANCE_2_0_SPECIAL|SEEDANCE_2_0_MINI|SEEDANCE_2_0_FAST") String model,
            @NotBlank @Pattern(regexp = "auto|16:9|4:3|1:1|3:4|9:16|21:9") String ratio,
            @Min(5) @Max(30) int durationSeconds,
            @NotBlank @Pattern(regexp = "480p|720p|1080p|4K") String resolution,
            @Pattern(regexp = "[a-f0-9]{64}") String quoteId,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            @Pattern(regexp = "VIDEO|DIGITAL_HUMAN") String sourceModule,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
            @Size(max = 10) List<@NotNull @Positive Long> referenceVideoAssetIds,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
            @Size(max = 10) List<@NotNull @Positive Long> referenceAudioAssetIds,
            // 创作场景，空或 free 即自由创作；不写进 JSON 的空值保持旧请求的幂等哈希不变。
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            @Pattern(regexp = "[a-z_]{1,40}") String sceneKey,
            // 提示词来自已采纳的方案时带上方案批次编号：方案已参考过场景，生成时不再附加场景参考。
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            @Positive Long planId) {
        public Create {
            referenceVideoAssetIds = referenceVideoAssetIds == null ? List.of() : referenceVideoAssetIds;
            referenceAudioAssetIds = referenceAudioAssetIds == null ? List.of() : referenceAudioAssetIds;
        }
        public Create(String requestKey, String prompt, List<Long> references, Long videoReference,
                      String model, String ratio, int seconds, String resolution, String quoteId, String sourceModule,
                      List<Long> videoReferences, List<Long> audioReferences) {
            this(requestKey, prompt, references, videoReference, model, ratio, seconds, resolution, quoteId, sourceModule,
                    videoReferences, audioReferences, null, null);
        }
        public Create(String requestKey, String prompt, List<Long> references, Long videoReference,
                      String model, String ratio, int seconds, String resolution, String quoteId, String sourceModule) {
            this(requestKey, prompt, references, videoReference, model, ratio, seconds, resolution, quoteId, sourceModule, null, null);
        }
        public Create(String requestKey, String prompt, List<Long> references, Long videoReference,
                      String model, String ratio, int seconds, String resolution, String quoteId) {
            this(requestKey, prompt, references, videoReference, model, ratio, seconds, resolution, quoteId, null);
        }
        public Create(String requestKey, String prompt, List<Long> references, Long videoReference,
                      String model, String ratio, int seconds, String resolution) {
            this(requestKey, prompt, references, videoReference, model, ratio, seconds, resolution, null, null);
        }

        // Older saved workflows and clients use a single video reference.
        public List<Long> videoReferences() {
            if (referenceVideoAssetId == null || referenceVideoAssetIds.contains(referenceVideoAssetId)) return referenceVideoAssetIds;
            var references = new java.util.ArrayList<Long>();
            references.add(referenceVideoAssetId);
            references.addAll(referenceVideoAssetIds);
            return List.copyOf(references);
        }
    }

    public record MaterialLimits(int image, int video, int audio) {}

    public record Capability(
            String id,
            String label,
            int maxDurationSeconds,
            List<String> resolutions,
            MaterialLimits materialLimits) {}

    public record History(Long id, String prompt, String model, String ratio,
                          int durationSeconds, String resolution, String status, Instant createdAt,
                          String outputUrl, Integer actualWidth, Integer actualHeight, Integer actualDurationMs,
                          String sceneKey) {
        public History(Long id, String prompt, String model, String ratio,
                       int durationSeconds, String resolution, String status, Instant createdAt) {
            this(id, prompt, model, ratio, durationSeconds, resolution, status, createdAt, null, null, null, null, null);
        }
    }

    public record Reference(Long assetId, String name, String url) {}

    public record Retry(@NotNull @Positive Long taskId, @Pattern(regexp = "[a-f0-9]{64}") String quoteId) {}

    public record View(
            Long id,
            String requestKey,
            String prompt,
            List<Long> referenceImageAssetIds,
            Long referenceVideoAssetId,
            String model,
            String ratio,
            int durationSeconds,
            String resolution,
            String status,
            String stage,
            int progress,
            String providerStatus,
            String error,
            String outputUrl,
            Long outputAssetId,
            Long taskId,
            Instant createdAt,
            List<Reference> referenceImages,
            Reference referenceVideo,
            String errorCode,
            boolean retryable,
            String retryStage,
            boolean retryMayCharge,
            String retryHint,
            Integer actualWidth,
            Integer actualHeight,
            Integer actualDurationMs,
            com.wuyao.growth.billing.VideoPricingService.Quote billingQuote,
            Long chargedCredits,
            List<Long> referenceVideoAssetIds,
            List<Long> referenceAudioAssetIds,
            List<Reference> referenceVideos,
            List<Reference> referenceAudios,
            String sceneKey,
            Long planId) {}
}
