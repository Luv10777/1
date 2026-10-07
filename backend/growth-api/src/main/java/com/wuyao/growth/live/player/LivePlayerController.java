package com.wuyao.growth.live.player;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LivePlayerController {
    private final LivePlayerService players;

    @PostMapping("/live-sessions/{id}/player/pairing")
    public ApiResponse<LivePlayerDtos.Pairing> issue(@PathVariable Long id,
                                                    @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(players.issue(id, me.userId()));
    }

    @PostMapping("/live-sessions/{id}/player/revoke")
    public ApiResponse<Void> revoke(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal me) {
        players.revoke(id, me.userId());
        return ApiResponse.ok(null);
    }

    @GetMapping("/live-sessions/{id}/player/status")
    public ApiResponse<LivePlayerDtos.Status> status(@PathVariable Long id,
                                                    @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(players.status(id, me.userId()));
    }

    @PostMapping("/live-sessions/{id}/player/commands")
    public ApiResponse<Map<String, Boolean>> enqueue(@PathVariable Long id,
                                                     @Valid @RequestBody LivePlayerDtos.Command command,
                                                     @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(Map.of("enqueued", players.enqueue(id, me.userId(), command)));
    }

    /** Public, content-free sound-check fixture. This is a tone, not synthesized speech. */
    @GetMapping(value = "/player/test-audio.wav", produces = "audio/wav")
    public ResponseEntity<byte[]> testAudio() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(PlayerTestAudio.WAV);
    }
}
