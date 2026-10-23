package com.wuyao.growth.knowledge;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService documents;

    @GetMapping("/stores/{storeId}/knowledge-documents")
    public ApiResponse<List<KnowledgeDocumentDtos.DocumentView>> listForStore(
            @PathVariable Long storeId, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(documents.list(storeId, null, me.userId()));
    }

    @GetMapping("/brands/{brandId}/knowledge-documents")
    public ApiResponse<List<KnowledgeDocumentDtos.DocumentView>> listForBrand(
            @PathVariable Long brandId, @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(documents.list(null, brandId, me.userId()));
    }

    @PostMapping("/stores/{storeId}/knowledge-documents")
    public ApiResponse<KnowledgeDocumentDtos.DocumentView> createForStore(
            @PathVariable Long storeId, @Valid @RequestBody KnowledgeDocumentDtos.CreateRequest request,
            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(documents.create(storeId, null, request.assetId(), me.userId()));
    }

    @PostMapping("/brands/{brandId}/knowledge-documents")
    public ApiResponse<KnowledgeDocumentDtos.DocumentView> createForBrand(
            @PathVariable Long brandId, @Valid @RequestBody KnowledgeDocumentDtos.CreateRequest request,
            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(documents.create(null, brandId, request.assetId(), me.userId()));
    }

    @GetMapping("/knowledge-documents/{id}")
    public ApiResponse<KnowledgeDocumentDtos.DocumentDetail> get(@PathVariable Long id,
                                                                 @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(documents.get(id, me.userId()));
    }

    @PostMapping("/knowledge-documents/{id}/retry")
    public ApiResponse<KnowledgeDocumentDtos.DocumentView> retry(@PathVariable Long id,
                                                                 @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(documents.retry(id, me.userId()));
    }

    @DeleteMapping("/knowledge-documents/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal me) {
        documents.delete(id, me.userId());
        return ApiResponse.ok();
    }

    @PostMapping("/knowledge-suggestions/{id}/accept")
    public ApiResponse<KnowledgeDocumentDtos.SuggestionView> accept(
            @PathVariable Long id, @Valid @RequestBody(required = false) KnowledgeDocumentDtos.AcceptRequest request,
            @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(documents.accept(id, request, me.userId()));
    }

    @PostMapping("/knowledge-suggestions/{id}/dismiss")
    public ApiResponse<KnowledgeDocumentDtos.SuggestionView> dismiss(@PathVariable Long id,
                                                                     @AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(documents.dismiss(id, me.userId()));
    }
}
