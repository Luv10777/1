package com.wuyao.vimax.controller;

import com.wuyao.vimax.dto.ApiResponse;
import com.wuyao.vimax.entity.WorkflowStep;
import com.wuyao.vimax.service.review.HumanReviewQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 人工审核 Controller
 *
 * Phase 7: 前端集成API
 */
@RestController
@RequestMapping("/review")
@RequiredArgsConstructor
@Slf4j
public class ReviewController {

    private final HumanReviewQueueService reviewQueueService;

    /**
     * 获取待审核列表
     */
    @GetMapping("/pending")
    public ApiResponse<List<WorkflowStep>> getPendingReviews(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId) {

        log.info("获取待审核列表: userId={}", userId);

        List<WorkflowStep> pending = reviewQueueService.getPendingReviewsForUser(userId);
        return ApiResponse.success(pending);
    }

    /**
     * 提交审核结果
     */
    @PostMapping("/{stepId}/submit")
    public ApiResponse<String> submitReview(
            @PathVariable Long stepId,
            @RequestParam boolean approved,
            @RequestParam(required = false) String comment,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId) {

        log.info("提交审核: stepId={}, approved={}, userId={}", stepId, approved, userId);

        reviewQueueService.submitReview(stepId, approved, comment, userId);
        return ApiResponse.success("审核已提交");
    }

    /**
     * 获取审核历史
     */
    @GetMapping("/history")
    public ApiResponse<List<WorkflowStep>> getReviewHistory(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId,
            @RequestParam(defaultValue = "20") int limit) {

        log.info("获取审核历史: userId={}, limit={}", userId, limit);

        List<WorkflowStep> history = reviewQueueService.getReviewHistoryForUser(userId, limit);
        return ApiResponse.success(history);
    }
}
