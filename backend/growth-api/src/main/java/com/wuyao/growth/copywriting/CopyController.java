package com.wuyao.growth.copywriting;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import com.wuyao.growth.common.web.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/copy")
@RequiredArgsConstructor
public class CopyController {
    private final CopyService service;
    private final CopyVoiceService voices;
    private final CopyAlmanacService almanacs;

    @GetMapping("/config") public ApiResponse<CopyDtos.Config> config() { return ApiResponse.ok(service.config()); }

    @GetMapping("/almanac/today")
    public ApiResponse<CopyDtos.Almanac> almanac(@RequestParam Long storeId, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(almanacs.today(storeId, me.userId()));
    }

    @PostMapping("/batches")
    public ApiResponse<CopyDtos.View> create(@Valid @RequestBody CopyDtos.Create request, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.create(request, me.userId()));
    }

    @GetMapping("/batches")
    public ApiResponse<PageResult<CopyDtos.History>> history(@RequestParam Long storeId, @RequestParam(required = false) String platform,
                                                            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                                                            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.history(storeId, platform, page, size, me.userId()));
    }

    @GetMapping("/batches/{id}")
    public ApiResponse<CopyDtos.View> get(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.get(id, me.userId()));
    }

    @PostMapping("/batches/{id}/versions/{index}/rewrite")
    public ApiResponse<CopyDtos.View> rewrite(@PathVariable Long id, @PathVariable int index, @Valid @RequestBody CopyDtos.Rewrite request,
                                             @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.rewrite(id, index, request, me.userId()));
    }

    @PostMapping("/journal")
    public ApiResponse<CopyDtos.JournalEntry> finalizeVersion(@Valid @RequestBody CopyDtos.Finalize request, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.finalizeVersion(request, me.userId()));
    }

    @GetMapping("/journal")
    public ApiResponse<PageResult<CopyDtos.JournalEntry>> journal(@RequestParam Long storeId, @RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(service.journal(storeId, page, size, me.userId()));
    }

    @GetMapping("/voice")
    public ApiResponse<CopyDtos.Voice> voice(@RequestParam Long storeId, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(voices.get(storeId, me.userId()));
    }

    @PutMapping("/voice")
    public ApiResponse<CopyDtos.Voice> updateVoice(@Valid @RequestBody CopyDtos.VoiceUpdate request, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(voices.update(request, me.userId()));
    }
}
