package com.wuyao.growth.knowledge;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import com.wuyao.growth.common.web.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    @GetMapping("/stores/{storeId}/knowledge-sets")
    public ApiResponse<PageResult<KnowledgeDtos.SetView>> listSets(
            @PathVariable Long storeId,
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(knowledgeService.listSets(storeId, kind, page, size, me.userId()));
    }

    @PostMapping("/stores/{storeId}/knowledge-sets")
    public ApiResponse<KnowledgeDtos.SetView> createSet(
            @PathVariable Long storeId,
            @Valid @RequestBody KnowledgeDtos.CreateSetRequest request,
            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(knowledgeService.createSet(storeId, request, me.userId()));
    }

    @GetMapping("/knowledge-sets/{id}")
    public ApiResponse<KnowledgeDtos.SetView> getSet(@PathVariable Long id,
                                                     @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(knowledgeService.getSet(id, me.userId()));
    }

    @PatchMapping("/knowledge-sets/{id}")
    public ApiResponse<KnowledgeDtos.SetView> updateSet(
            @PathVariable Long id,
            @Valid @RequestBody KnowledgeDtos.UpdateSetRequest request,
            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(knowledgeService.updateSet(id, request, me.userId()));
    }

    @DeleteMapping("/knowledge-sets/{id}")
    public ApiResponse<KnowledgeDtos.SetView> archiveSet(@PathVariable Long id,
                                                         @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(knowledgeService.archive(id, me.userId()));
    }

    @GetMapping("/knowledge-sets/{id}/entries")
    public ApiResponse<PageResult<KnowledgeDtos.EntryView>> listEntries(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(knowledgeService.listEntries(id, page, size, me.userId()));
    }

    @PostMapping("/knowledge-sets/{id}/entries")
    public ApiResponse<KnowledgeDtos.EntryView> createEntry(
            @PathVariable Long id,
            @Valid @RequestBody KnowledgeDtos.CreateEntryRequest request,
            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(knowledgeService.createEntry(id, request, me.userId()));
    }

    @PatchMapping("/knowledge-entries/{id}")
    public ApiResponse<KnowledgeDtos.EntryView> updateEntry(
            @PathVariable Long id,
            @Valid @RequestBody KnowledgeDtos.UpdateEntryRequest request,
            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(knowledgeService.updateEntry(id, request, me.userId()));
    }

    @DeleteMapping("/knowledge-entries/{id}")
    public ApiResponse<Void> deleteEntry(@PathVariable Long id,
                                         @AuthenticationPrincipal AuthPrincipal me) {
        knowledgeService.deleteEntry(id, me.userId());
        return ApiResponse.ok();
    }

    @PostMapping("/knowledge-sets/{id}/publish")
    public ApiResponse<KnowledgeDtos.SetView> publish(@PathVariable Long id,
                                                      @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(knowledgeService.publish(id, me.userId()));
    }

    @PostMapping("/knowledge-sets/{id}/disable")
    public ApiResponse<KnowledgeDtos.SetView> disable(@PathVariable Long id,
                                                      @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(knowledgeService.disable(id, me.userId()));
    }

    @PostMapping("/knowledge-sets/{id}/archive")
    public ApiResponse<KnowledgeDtos.SetView> archive(@PathVariable Long id,
                                                      @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(knowledgeService.archive(id, me.userId()));
    }

    @GetMapping("/stores/{storeId}/knowledge-context")
    public ApiResponse<KnowledgeDtos.KnowledgeContext> context(
            @PathVariable Long storeId,
            @RequestParam(required = false, name = "productId") List<Long> productIds,
            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(knowledgeService.context(storeId, productIds, me.userId()));
    }
}
