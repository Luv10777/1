package com.wuyao.growth.voice;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api") @RequiredArgsConstructor
public class VoiceController {
    private final VoiceSampleService samples;
    private final VoiceService voices;
    @GetMapping("/voice/capabilities")
    public ApiResponse<VoiceDtos.Capabilities> capabilities() { return ApiResponse.ok(voices.capabilities()); }
    @GetMapping("/stores/{storeId}/voice-samples")
    public ApiResponse<List<VoiceDtos.SampleView>> list(@PathVariable Long storeId, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(samples.list(storeId, me.userId()));
    }
    @PostMapping("/stores/{storeId}/voice-samples/upload-url")
    public ApiResponse<VoiceDtos.UploadTicket> upload(@PathVariable Long storeId, @Valid @RequestBody VoiceDtos.UploadRequest request, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(samples.upload(storeId, request, me.userId()));
    }
    @PostMapping("/voice-samples/{id}/confirm")
    public ApiResponse<VoiceDtos.SampleView> confirm(@PathVariable Long id, @Valid @RequestBody VoiceDtos.ConfirmRequest request, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(samples.confirm(id, request.sizeBytes(), me.userId()));
    }
    @GetMapping("/voice-samples/{id}/download-url")
    public ApiResponse<VoiceDtos.DownloadTicket> download(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(samples.download(id, me.userId()));
    }
    @PatchMapping("/voice-samples/{id}")
    public ApiResponse<VoiceDtos.SampleView> rename(@PathVariable Long id, @Valid @RequestBody VoiceDtos.RenameRequest request, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(samples.rename(id, request.name(), me.userId()));
    }
    /** 管理员调整这个声音开放给哪些门店。 */
    @PutMapping("/voice-samples/{id}/stores")
    public ApiResponse<VoiceDtos.SampleView> setStores(@PathVariable Long id, @Valid @RequestBody VoiceDtos.StoresRequest request, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(samples.setStores(id, request.storeIds(), me.userId()));
    }
    @PostMapping("/voice-samples/{id}/clone")
    public ApiResponse<VoiceDtos.SampleView> cloneVoice(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(voices.cloneVoice(id, me.userId()));
    }
    @PostMapping("/voice-samples/{id}/refresh")
    public ApiResponse<VoiceDtos.SampleView> refresh(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(voices.refresh(id, me.userId()));
    }
    @DeleteMapping("/voice-samples/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal me) {
        voices.delete(id, me.userId()); return ApiResponse.ok(null);
    }
    @PostMapping("/stores/{storeId}/voice/synthesize")
    public ApiResponse<VoiceDtos.SpeechResult> synthesize(@PathVariable Long storeId, @Valid @RequestBody VoiceDtos.SpeechRequest request, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(voices.synthesize(storeId, request, me.userId()));
    }
}
