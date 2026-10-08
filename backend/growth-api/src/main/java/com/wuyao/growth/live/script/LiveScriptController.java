package com.wuyao.growth.live.script;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LiveScriptController {
    private final LiveScriptService scripts;

    @GetMapping("/live-sessions/{id}/auto-script")
    public ApiResponse<LiveScriptDtos.Status> status(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(scripts.status(id, me.userId()));
    }

    @PostMapping("/live-sessions/{id}/auto-script/start")
    public ApiResponse<LiveScriptDtos.Status> start(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(scripts.start(id, me.userId()));
    }

    @PostMapping("/live-sessions/{id}/auto-script/stop")
    public ApiResponse<LiveScriptDtos.Status> stop(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(scripts.stop(id, me.userId()));
    }

    /** Recent utterances of every kind, newest first: what was queued, what played, what failed and why. */
    @GetMapping("/live-sessions/{id}/speech-items")
    public ApiResponse<List<LiveScriptDtos.ItemView>> items(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(scripts.recentItems(id, me.userId()));
    }
}
