package com.wuyao.nexus.controller;

import com.wuyao.nexus.dto.ApiResponse;
import com.wuyao.nexus.dto.KnowledgeRequest;
import com.wuyao.nexus.dto.KnowledgeResponse;
import com.wuyao.nexus.service.KnowledgeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    @GetMapping
    public ApiResponse<Page<KnowledgeResponse>> list(
            @RequestAttribute("tenantId") Long tenantId,
            Pageable pageable) {
        Page<KnowledgeResponse> knowledge = knowledgeService.list(tenantId, pageable);
        return ApiResponse.success(knowledge);
    }

    @GetMapping("/{id}")
    public ApiResponse<KnowledgeResponse> get(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId) {
        KnowledgeResponse knowledge = knowledgeService.get(id, tenantId);
        return ApiResponse.success(knowledge);
    }

    @PostMapping
    public ApiResponse<KnowledgeResponse> create(
            @RequestAttribute("tenantId") Long tenantId,
            @RequestAttribute("userId") Long userId,
            @Valid @RequestBody KnowledgeRequest request) {
        KnowledgeResponse knowledge = knowledgeService.create(tenantId, userId, request);
        return ApiResponse.success(knowledge, "知识创建成功");
    }

    @PutMapping("/{id}")
    public ApiResponse<KnowledgeResponse> update(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId,
            @Valid @RequestBody KnowledgeRequest request) {
        KnowledgeResponse knowledge = knowledgeService.update(id, tenantId, request);
        return ApiResponse.success(knowledge, "知识更新成功");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId) {
        knowledgeService.delete(id, tenantId);
        return ApiResponse.success(null, "知识删除成功");
    }

    @PostMapping("/{id}/verify")
    public ApiResponse<Void> verify(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId,
            @RequestAttribute("userId") Long userId) {
        knowledgeService.verify(id, tenantId, userId);
        return ApiResponse.success(null, "知识验证成功");
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<Void> publish(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId) {
        knowledgeService.publish(id, tenantId);
        return ApiResponse.success(null, "知识发布成功");
    }
}
