package com.wuyao.vimax.service.review;

import com.wuyao.vimax.entity.WorkflowStep;
import com.wuyao.vimax.repository.WorkflowStepRepository;
import com.wuyao.vimax.service.workflow.WorkflowStepManagementService;
import com.wuyao.vimax.service.workflow.WorkflowEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 人工审核队列服务
 *
 * Phase 5: 待审核任务列表、审核通知、审核历史
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HumanReviewQueueService {

    private final WorkflowStepRepository stepRepository;
    private final WorkflowStepManagementService stepManagementService;
    private final WorkflowEngineService workflowEngineService;

    /**
     * 获取待审核任务列表
     */
    public List<WorkflowStep> getPendingReviews(Long tenantId) {
        // TODO: 添加租户过滤
        return stepRepository.findAll().stream()
            .filter(step -> step.getRequiresHumanReview() && "RUNNING".equals(step.getState()))
            .collect(Collectors.toList());
    }

    /**
     * 获取特定用户的待审核任务
     */
    public List<WorkflowStep> getPendingReviewsForUser(Long userId) {
        // TODO: 根据用户权限过滤
        return getPendingReviews(1L); // 临时使用租户ID=1
    }

    /**
     * 提交审核结果
     */
    @Transactional
    public void submitReview(Long stepId, boolean approved, String comment, Long reviewerId) {
        log.info("提交审核: stepId={}, approved={}, reviewerId={}", stepId, approved, reviewerId);

        WorkflowStep step = stepRepository.findById(stepId)
            .orElseThrow(() -> new IllegalArgumentException("步骤不存在"));

        if (!step.getRequiresHumanReview()) {
            throw new IllegalStateException("该步骤不需要人工审核");
        }

        // 记录审核结果
        stepManagementService.reviewStep(stepId, approved, comment, reviewerId);

        if (approved) {
            // 审核通过，继续执行下一步
            workflowEngineService.executeNextStep(step.getWorkflowRunId());
        } else {
            // 审核拒绝，工作流失败
            workflowEngineService.failWorkflow(step.getWorkflowRunId(), "人工审核拒绝: " + comment);
        }
    }

    /**
     * 获取审核历史
     */
    public List<WorkflowStep> getReviewHistory(Long tenantId, int limit) {
        // TODO: 添加租户过滤和分页
        return stepRepository.findAll().stream()
            .filter(step -> step.getRequiresHumanReview() && step.getHumanReviewedAt() != null)
            .limit(limit)
            .collect(Collectors.toList());
    }

    /**
     * 获取特定用户的审核历史
     */
    public List<WorkflowStep> getReviewHistoryForUser(Long userId, int limit) {
        return stepRepository.findAll().stream()
            .filter(step -> step.getHumanReviewedBy() != null && step.getHumanReviewedBy().equals(userId))
            .limit(limit)
            .collect(Collectors.toList());
    }
}
