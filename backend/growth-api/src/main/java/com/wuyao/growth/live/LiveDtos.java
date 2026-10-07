package com.wuyao.growth.live;

import jakarta.validation.constraints.*;
import jakarta.validation.Valid;

import java.time.Instant;
import java.util.List;

public final class LiveDtos {

    private LiveDtos() {
    }

    public record CreateRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 200) String roomId,
            @Size(max = 100) List<@NotNull @Positive Long> productIds,
            @Valid Config config,
            @Pattern(regexp = "loopback_adapter|pc_soundcard|speaker_pickup") String audioRoute,
            @Pattern(regexp = "untested|confirmed|failed") String audioTestStatus) {
        public CreateRequest(String name, String roomId, List<Long> productIds) {
            this(name, roomId, productIds, null, null, null);
        }

        public CreateRequest(String name, String roomId, List<Long> productIds, Config config) {
            this(name, roomId, productIds, config, null, null);
        }
    }

    public record UpdateRequest(
            @Size(max = 160) String name,
            @Size(max = 200) String roomId,
            @Size(max = 100) List<@NotNull @Positive Long> productIds,
            @PositiveOrZero Long version,
            @Valid Config config,
            @Pattern(regexp = "loopback_adapter|pc_soundcard|speaker_pickup") String audioRoute,
            @Pattern(regexp = "untested|confirmed|failed") String audioTestStatus) {
        public UpdateRequest(String name, String roomId, List<Long> productIds, Long version) {
            this(name, roomId, productIds, version, null, null, null);
        }

        public UpdateRequest(String name, String roomId, List<Long> productIds, Long version, Config config) {
            this(name, roomId, productIds, version, config, null, null);
        }
    }

    /**
     * rotateRoles and rotationSelection are no longer written: the co-hosts in voiceRoles are the
     * rotation. They stay so that sessions saved earlier still load and keep their behaviour.
     */
    public record Config(@Valid Tone tone,
                         @DecimalMin("1") @DecimalMax("5") Double urgency,
                         Boolean antiRepeat,
                         @Min(1) @Max(24) Integer dailyHours,
                         Boolean rotateRoles,
                         @Size(max = 20) List<@Size(max = 100) String> rotationSelection,
                         @Size(max = 20) List<@Valid VoiceRole> voiceRoles,
                         Boolean aiDisclosure,
                         @Valid Persona persona,
                         /* Viewers' questions are answered by the first co-host, who then no longer narrates. */
                         Boolean replyByCohost) {
        public Config(Tone tone, Double urgency, Boolean antiRepeat, Integer dailyHours,
                      Boolean rotateRoles, List<String> rotationSelection, List<VoiceRole> voiceRoles) {
            this(tone, urgency, antiRepeat, dailyHours, rotateRoles, rotationSelection, voiceRoles, true, null, null);
        }
    }

    /**
     * How the host presents itself in generated narration. The name is restricted to letters so that
     * it can be quoted to the text model verbatim; the style is one of a fixed set of labels.
     */
    public record Persona(@Pattern(regexp = "[\\p{IsHan}A-Za-z]{0,12}", message = "主播称呼只能是 12 个以内的汉字或字母") String name,
                          @Size(max = 20) String style) { }

    public record Tone(@Size(max = 100) String opening,
                       @Size(max = 20) List<@NotBlank @Size(max = 100) String> pain,
                       @Size(max = 20) List<@NotBlank @Size(max = 100) String> detail) { }

    public record VoiceRole(@NotBlank @Size(max = 100) String id,
                            @Pattern(regexp = "host|cohost|none") String role) { }

    public record QaUpdateRequest(@NotBlank @Size(max = 500) String question,
                                  @NotBlank @Size(max = 4000) String answer,
                                  @NotNull @PositiveOrZero Long version) { }

    /** targetId is a selected product ID for PRODUCT_FAQ, or a knowledge set ID for STORE_KNOWLEDGE. */
    public record QaRequest(
            @NotBlank @Size(max = 500) String question,
            @NotBlank @Size(max = 4000) String answer,
            @Pattern(regexp = "SESSION|PRODUCT_FAQ|STORE_KNOWLEDGE") String persistMode,
            @Positive Long targetId) {
    }

    public record ProductRequest(@NotNull @Positive Long productId, @PositiveOrZero Integer sortOrder) {
    }

    public record View(Long id, Long storeId, String name, String roomId, String status,
                       Integer knowledgeVersion, Long version, Instant startedAt, Instant endedAt,
                       List<Long> productIds, List<SnapshotView> knowledge, Config config,
                       String audioRoute, String audioTestStatus, boolean playerPaired,
                       Instant playerLastHeartbeatAt) {
    }

    /** What a list of sessions needs. The full view carries the whole knowledge snapshot of each one. */
    public record Summary(Long id, Long storeId, String name, String status,
                          Instant startedAt, Instant endedAt, Instant updatedAt) {
    }

    public record DuplicateRequest(@Size(max = 160) String name) {
    }

    public record AudioRouteRequest(
            @Pattern(regexp = "loopback_adapter|pc_soundcard|speaker_pickup") String audioRoute,
            @Pattern(regexp = "untested|confirmed|failed") String audioTestStatus) {
    }

    public record SnapshotView(Long id, String sourceType, Long sourceId,
                               String question, String answer, Integer priority) {
    }

    public record QaView(Long id, String question, String answer, String persistMode, Long targetId, Long version) {
    }

    public record RealtimeView(String status, String message, List<RealtimeItem> items) { }

    /** A question viewers asked that the session's material could not answer, however many times it came up. */
    public record UnansweredQuestion(String text, int count, Instant lastAskedAt) { }

    /** One viewer comment with what was answered: state is the comment's status, note why it went unanswered. */
    public record RealtimeItem(String id, String kind, String text, String answer, String source,
                               String state, String note, String provider, Instant createdAt) { }
}
