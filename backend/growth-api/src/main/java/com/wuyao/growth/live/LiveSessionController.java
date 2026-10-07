package com.wuyao.growth.live;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import com.wuyao.growth.live.reply.LiveCommentFeed;
import com.wuyao.growth.live.script.LiveScriptService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class LiveSessionController {

    private final LiveSessionService service;
    private final LiveScriptService scripts;
    private final LiveCommentFeed comments;

    @GetMapping("/stores/{storeId}/live-sessions")
    public ApiResponse<List<LiveDtos.Summary>> list(@PathVariable Long storeId,
                                                   @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.list(storeId, me.userId()));
    }

    @PostMapping("/stores/{storeId}/live-sessions")
    public ApiResponse<LiveDtos.View> create(@PathVariable Long storeId,
                                            @Valid @RequestBody LiveDtos.CreateRequest request,
                                            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.create(storeId, request, me.userId()));
    }

    @GetMapping("/live-sessions/{id}")
    public ApiResponse<LiveDtos.View> get(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.get(id, me.userId()));
    }

    @PatchMapping("/live-sessions/{id}")
    public ApiResponse<LiveDtos.View> update(@PathVariable Long id,
                                            @Valid @RequestBody LiveDtos.UpdateRequest request,
                                            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.update(id, request, me.userId()));
    }

    @DeleteMapping("/live-sessions/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal me) {
        service.delete(id, me.userId());
        return ApiResponse.ok(null);
    }

    @PostMapping("/live-sessions/{id}/duplicate")
    public ApiResponse<LiveDtos.View> duplicate(@PathVariable Long id,
                                               @Valid @RequestBody(required = false) LiveDtos.DuplicateRequest request,
                                               @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.duplicate(id, request, me.userId()));
    }

    @PatchMapping("/live-sessions/{id}/audio-route")
    public ApiResponse<LiveDtos.View> updateAudioRoute(@PathVariable Long id,
                                                       @Valid @RequestBody LiveDtos.AudioRouteRequest request,
                                                       @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.updateAudioRoute(id, request, me.userId()));
    }

    @PostMapping("/live-sessions/{id}/parse-link")
    public ApiResponse<LiveDtos.View> parse(@PathVariable Long id, @RequestParam String roomId,
                                           @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.parseLink(id, roomId, me.userId()));
    }

    @PostMapping("/live-sessions/{id}/start")
    public ApiResponse<LiveDtos.View> start(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.start(id, me.userId()));
    }

    @PostMapping("/live-sessions/{id}/pause")
    public ApiResponse<LiveDtos.View> pause(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.pause(id, me.userId()));
    }

    @PostMapping("/live-sessions/{id}/resume")
    public ApiResponse<LiveDtos.View> resume(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal me) {
        LiveDtos.View view = service.resume(id, me.userId());
        // Narration stopped topping up while paused; pick it up again if it is still switched on.
        scripts.replenishQuietly(id);
        return ApiResponse.ok(view);
    }

    @PostMapping("/live-sessions/{id}/end")
    public ApiResponse<LiveDtos.View> end(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal me) {
        LiveDtos.View view = service.end(id, me.userId());
        scripts.sessionEnded(id);
        return ApiResponse.ok(view);
    }

    @GetMapping("/live-sessions/{id}/qa")
    public ApiResponse<List<LiveDtos.QaView>> qa(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.listQa(id, me.userId()));
    }

    @GetMapping("/live-sessions/{id}/realtime")
    public ApiResponse<LiveDtos.RealtimeView> realtime(@PathVariable Long id,
                                                        @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(comments.realtime(id, me.userId()));
    }

    @GetMapping("/live-sessions/{id}/unanswered-questions")
    public ApiResponse<List<LiveDtos.UnansweredQuestion>> unanswered(@PathVariable Long id,
                                                                      @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(comments.unanswered(id, me.userId()));
    }

    @PostMapping("/live-sessions/{id}/qa")
    public ApiResponse<LiveDtos.QaView> addQa(@PathVariable Long id,
                                            @Valid @RequestBody LiveDtos.QaRequest request,
                                            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.addQa(id, request, me.userId()));
    }

    @PutMapping("/live-sessions/{id}/qa/{qaId}")
    public ApiResponse<LiveDtos.QaView> updateQa(@PathVariable Long id, @PathVariable Long qaId,
                                               @Valid @RequestBody LiveDtos.QaUpdateRequest request,
                                               @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.updateQa(id, qaId, request, me.userId()));
    }

    @DeleteMapping("/live-sessions/{id}/qa/{qaId}")
    public ApiResponse<Void> deleteQa(@PathVariable Long id, @PathVariable Long qaId,
                                      @RequestParam Long version,
                                      @AuthenticationPrincipal AuthPrincipal me) {
        service.deleteQa(id, qaId, version, me.userId());
        return ApiResponse.ok(null);
    }
}
