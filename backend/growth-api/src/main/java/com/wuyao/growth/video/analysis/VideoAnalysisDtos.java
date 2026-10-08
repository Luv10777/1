package com.wuyao.growth.video.analysis;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class VideoAnalysisDtos {
    private VideoAnalysisDtos() {}
    public record Upload(@NotBlank @Size(max = 200) String name,
                         @NotBlank @Pattern(regexp = "video/mp4|video/quicktime|video/mov") String mimeType,
                         @NotNull @Positive Long sizeBytes) {}
    public record Create(@NotNull @Positive Long assetId,
                         @NotBlank @Size(max = 80) String requestKey,
                         @NotBlank @Pattern(regexp = "ai|real") String mode,
                         @Size(max = 2000) String reverseNeed) {}
    public record Rename(@NotBlank @Size(max = 200) String name) {}
    public record Import(@NotBlank @Size(max = 4096) String url,
                         @NotBlank @Size(max = 80) String requestKey,
                         @NotBlank @Pattern(regexp = "ai|real") String mode,
                         @Size(max = 2000) String reverseNeed) {}
    public record FrameView(double seconds, String imageUrl) {}
    public record View(Long id, Long assetId, String name, String mode, String status, int progress,
                       String reverseNeed, Integer durationMs, Integer width, Integer height,
                       String videoUrl, List<FrameView> frames, Map<String, Object> result,
                       String errorMessage, Instant createdAt) {}
    public record Limits(long maxBytes, int maxDurationSeconds, int maxFrames, boolean configured, boolean audioConfigured) {}
}
