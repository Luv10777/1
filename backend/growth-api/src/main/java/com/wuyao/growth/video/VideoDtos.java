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
            @NotBlank @Size(max = 2000) String prompt,
            @NotNull @Size(max = 6) List<@Positive Long> referenceImageAssetIds,
            @Positive Long referenceVideoAssetId,
            @NotBlank @Pattern(regexp = "SEEDANCE_2_5|SEEDANCE_2_0|SEEDANCE_2_0_MINI|SEEDANCE_2_0_FAST") String model,
            @NotBlank @Pattern(regexp = "9:16|16:9|1:1") String ratio,
            @Min(5) @Max(30) int durationSeconds,
            @NotBlank @Pattern(regexp = "480p|720p|1080p|4K") String resolution) {}

    public record Capability(
            String id,
            String label,
            int maxDurationSeconds,
            List<String> resolutions) {}

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
            Instant createdAt) {}
}
