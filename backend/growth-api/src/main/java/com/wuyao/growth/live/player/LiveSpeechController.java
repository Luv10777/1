package com.wuyao.growth.live.player;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LiveSpeechController {
    private final LiveSpeechService speech;
    private final LivePlayerService players;
    private final LiveCommentIntake comments;
    private final MockCommentProvider simulated;
    private final LiveRoomCommentProvider liveRoom;

    @PostMapping("/live-sessions/{id}/speech")
    public ApiResponse<LiveSpeechDtos.SpeechResult> speak(@PathVariable Long id,
                                                       @Valid @RequestBody LiveSpeechDtos.SpeechRequest request,
                                                       @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(speech.speak(id, request, me.userId()));
    }

    @PostMapping("/live-sessions/{id}/mock-comments")
    public ApiResponse<LiveSpeechDtos.CommentResult> comment(@PathVariable Long id,
                                                           @Valid @RequestBody LiveSpeechDtos.CommentRequest request,
                                                           @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(comments.receive(simulated, id, request, me.userId()));
    }

    /** A comment the merchant's desktop app read from their own live room. */
    @PostMapping("/live-sessions/{id}/comments")
    public ApiResponse<LiveSpeechDtos.CommentResult> liveRoomComment(@PathVariable Long id,
                                                                   @Valid @RequestBody LiveSpeechDtos.CommentRequest request,
                                                                   @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(comments.receive(liveRoom, id, request, me.userId()));
    }

    @GetMapping(value="/player/audio/{id}", produces="audio/wav")
    public ResponseEntity<byte[]> audio(@PathVariable String id, @RequestParam String token) {
        var scope = players.authenticate(token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .body(speech.audio(scope, id));
    }
}
