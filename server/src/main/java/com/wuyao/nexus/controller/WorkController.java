package com.wuyao.nexus.controller;

import com.wuyao.nexus.dto.ApiResponse;
import com.wuyao.nexus.dto.WorkRequest;
import com.wuyao.nexus.dto.WorkResponse;
import com.wuyao.nexus.service.WorkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/works")
@RequiredArgsConstructor
public class WorkController {

    private final WorkService workService;

    @GetMapping
    public ApiResponse<Page<WorkResponse>> list(
            @RequestAttribute("tenantId") Long tenantId,
            @RequestParam(required = false) String reviewStatus,
            @RequestParam(required = false) String type,
            Pageable pageable) {
        Page<WorkResponse> works = workService.list(tenantId, reviewStatus, type, pageable);
        return ApiResponse.success(works);
    }

    @GetMapping("/{id}")
    public ApiResponse<WorkResponse> get(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId) {
        WorkResponse work = workService.get(id, tenantId);
        return ApiResponse.success(work);
    }

    @PostMapping
    public ApiResponse<WorkResponse> create(
            @RequestAttribute("tenantId") Long tenantId,
            @RequestAttribute("userId") Long userId,
            @Valid @RequestBody WorkRequest request) {
        WorkResponse work = workService.create(tenantId, userId, request);
        return ApiResponse.success(work, "作品创建成功");
    }

    @PutMapping("/{id}")
    public ApiResponse<WorkResponse> update(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId,
            @Valid @RequestBody WorkRequest request) {
        WorkResponse work = workService.update(id, tenantId, request);
        return ApiResponse.success(work, "作品更新成功");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId) {
        workService.delete(id, tenantId);
        return ApiResponse.success(null, "作品删除成功");
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<Void> submitReview(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId,
            @RequestAttribute("userId") Long userId) {
        workService.submitReview(id, tenantId, userId);
        return ApiResponse.success(null, "作品已提交审核");
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<Void> approve(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId,
            @RequestAttribute("userId") Long userId,
            @RequestBody Map<String, String> body) {
        String notes = body.get("notes");
        workService.approve(id, tenantId, userId, notes);
        return ApiResponse.success(null, "作品审核通过");
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<Void> reject(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId,
            @RequestAttribute("userId") Long userId,
            @RequestBody Map<String, String> body) {
        String notes = body.get("notes");
        workService.reject(id, tenantId, userId, notes);
        return ApiResponse.success(null, "作品已驳回");
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<Void> publish(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId,
            @RequestBody Map<String, String[]> body) {
        String[] platforms = body.get("platforms");
        workService.publish(id, tenantId, platforms);
        return ApiResponse.success(null, "作品发布成功");
    }
}
