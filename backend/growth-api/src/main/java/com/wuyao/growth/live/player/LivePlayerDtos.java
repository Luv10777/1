package com.wuyao.growth.live.player;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class LivePlayerDtos {
    private LivePlayerDtos() {}

    public record Pairing(String token, String urlPath, Instant expiresAt) {}

    public record Status(boolean playerPaired, boolean connected, Instant playerLastHeartbeatAt,
                         int queueLength, String deviceType) {}

    public record Command(@NotBlank @Size(max = 100) String id,
                          @NotBlank @Pattern(regexp = "APPEND|INTERRUPT|CLEAR_REPLAY") String mode,
                          @NotBlank @Size(max = 4000) String text,
                          @NotBlank @Size(max = 2000) String audioUrl,
                          @Min(1) @Max(600000) long durationMillis,
                          @NotNull @Size(max = 500) List<@NotNull @Positive Long> pauseOffsetsMillis,
                          /* Where a reply's closing line starts; the player decides there whether to speak it. */
                          @Positive Long outroOffsetMillis) {
    }

    /** Internal result: tenant/session values are recovered only from a verified token. */
    public record Scope(Long tenantId, Long sessionId, Long pairingId, String sessionName) {}
}
