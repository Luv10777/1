package com.wuyao.growth.voice;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class VoiceDtos {
    private VoiceDtos() { }
    public record UploadRequest(@NotBlank @Size(max=100) String name,
                                @NotBlank @Pattern(regexp="audio/(wav|x-wav|mpeg|mp3|mp4|x-m4a)") String mimeType,
                                @AssertTrue(message="请确认声音属于本人或已获得授权") @NotNull Boolean consent) { }
    public record ConfirmRequest(@NotNull @Positive @Max(10485760) Long sizeBytes) { }
    public record RenameRequest(@NotBlank @Size(max=100) String name) { }
    /** @param stalled a clone submission that was interrupted; it can be submitted again or deleted */
    public record SampleView(Long id, Long storeId, String name, String status, String providerCode,
                             String providerVoiceId, Instant consentAt, Long consentBy,
                             String consentText, String storageKey, String errorMessage, boolean stalled) {
        static SampleView of(VoiceSample sample) {
            return new SampleView(sample.getId(), sample.getStoreId(), sample.getName(), sample.getStatus(),
                    sample.getProviderCode(), sample.getProviderVoiceId(), sample.getConsentAt(), sample.getConsentBy(),
                    sample.getConsentText(), sample.getStorageKey(), sample.getErrorMessage(),
                    VoiceSampleService.stalled(sample));
        }
    }
    public record UploadTicket(SampleView sample, String uploadUrl) { }
    public record DownloadTicket(String downloadUrl) { }
    public record Capabilities(String providerCode, boolean configured, boolean streaming,
                               String message, List<String> builtInVoices, String model) { }
    public record SpeechRequest(@NotBlank @Size(max=1000) String text, @Positive Long sampleId,
                                @Size(max=120) String builtInVoice) { }
    public record SpeechResult(String providerCode, String audioBase64, String mimeType,
                               long durationMillis, List<Long> pauseOffsetsMillis, long firstAudioMillis) { }
}
