package com.wuyao.vimax.service.workflow;

import com.wuyao.vimax.entity.WorkflowRun;
import com.wuyao.vimax.entity.WorkflowStep;
import com.wuyao.vimax.entity.MerchantFactSnapshot;
import com.wuyao.vimax.repository.WorkflowRunRepository;
import com.wuyao.vimax.repository.WorkflowStepRepository;
import com.wuyao.vimax.service.MerchantSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工作流步骤管理服务
 *
 * Phase 2.2: 管理工作流执行步骤
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowStepManagementService {

    private final WorkflowStepRepository stepRepository;
    private final WorkflowRunRepository runRepository;
    private final MerchantSnapshotService snapshotService;

    /**
     * 初始化工作流步骤
     *
     * @param workflowRunId 工作流运行ID
     */
    @Transactional
    public void initializeSteps(Long workflowRunId) {
        log.info("初始化工作流步骤: runId={}", workflowRunId);

        WorkflowRun run = runRepository.findById(workflowRunId)
            .orElseThrow(() -> new IllegalArgumentException("工作流运行不存在"));

        // 创建标准步骤序列
        createStep(workflowRunId, "VALIDATE_INPUT", "验证输入", "VALIDATION", 1, null);
        createStep(workflowRunId, "TEXT_UNDERSTANDING", "文本理解", "AI_GENERATION", 2, null);
        createStep(workflowRunId, "APPROVE_BRIEF", "审核脚本", "HUMAN_REVIEW", 3, null);
        createStep(workflowRunId, "IMAGE_GENERATION", "生成首帧", "AI_GENERATION", 4, null);
        createStep(workflowRunId, "APPROVE_IMAGE", "审核首帧", "HUMAN_REVIEW", 5, null);
        createStep(workflowRunId, "VIDEO_GENERATION", "生成视频", "AI_GENERATION", 6, null);
        createStep(workflowRunId, "QUALITY_CHECK", "质量检查", "VALIDATION", 7, null);
        createStep(workflowRunId, "APPROVE_VIDEO", "终审视频", "HUMAN_REVIEW", 8, null);
        createStep(workflowRunId, "FINALIZE", "完成入库", "FINALIZATION", 9, null);

        log.info("工作流步骤已初始化: runId={}, steps=9", workflowRunId);
    }

    /**
     * 创建单个步骤
     */
    private void createStep(Long runId, String code, String name, String type,
                           int order, Long dependsOn) {
        WorkflowStep step = new WorkflowStep();
        step.setWorkflowRunId(runId);
        step.setStepCode(code);
        step.setStepName(name);
        step.setStepType(type);
        step.setSequenceOrder(order);
        step.setDependsOnStepId(dependsOn);
        step.setState("PENDING");

        // 设置人工审核标志
        if ("HUMAN_REVIEW".equals(type)) {
            step.setRequiresHumanReview(true);
        }

        stepRepository.save(step);
    }

    /**
     * 开始步骤
     */
    @Transactional
    public void startStep(Long stepId) {
        log.info("开始步骤: stepId={}", stepId);

        WorkflowStep step = stepRepository.findById(stepId)
            .orElseThrow(() -> new IllegalArgumentException("步骤不存在"));

        step.setState("RUNNING");
        step.setStartedAt(LocalDateTime.now());
        stepRepository.save(step);
    }

    /**
     * 完成步骤
     */
    @Transactional
    public void completeStep(Long stepId, String outputData) {
        log.info("完成步骤: stepId={}", stepId);

        WorkflowStep step = stepRepository.findById(stepId)
            .orElseThrow(() -> new IllegalArgumentException("步骤不存在"));

        step.setState("COMPLETED");
        step.setOutputData(outputData);
        step.setCompletedAt(LocalDateTime.now());
        stepRepository.save(step);
    }

    /**
     * 步骤失败
     */
    @Transactional
    public void failStep(Long stepId, String errorMessage) {
        log.info("步骤失败: stepId={}", stepId);

        WorkflowStep step = stepRepository.findById(stepId)
            .orElseThrow(() -> new IllegalArgumentException("步骤不存在"));

        step.setState("FAILED");
        step.setErrorMessage(errorMessage);
        step.setFailedAt(LocalDateTime.now());
        step.setRetryCount(step.getRetryCount() + 1);
        stepRepository.save(step);
    }

    /**
     * 人工审核步骤
     */
    @Transactional
    public void reviewStep(Long stepId, boolean approved, String comment, Long reviewerId) {
        log.info("人工审核步骤: stepId={}, approved={}", stepId, approved);

        WorkflowStep step = stepRepository.findById(stepId)
            .orElseThrow(() -> new IllegalArgumentException("步骤不存在"));

        step.setHumanReviewedAt(LocalDateTime.now());
        step.setHumanReviewedBy(reviewerId);
        step.setHumanReviewResult(approved ? "APPROVED" : "REJECTED");
        step.setHumanReviewComment(comment);

        if (approved) {
            step.setState("COMPLETED");
            step.setCompletedAt(LocalDateTime.now());
        } else {
            step.setState("REJECTED");
            step.setFailedAt(LocalDateTime.now());
        }

        stepRepository.save(step);
    }

    /**
     * 查询工作流的所有步骤
     */
    public List<WorkflowStep> getSteps(Long workflowRunId) {
        return stepRepository.findByWorkflowRunIdOrderBySequenceOrderAsc(workflowRunId);
    }

    /**
     * 查询下一个待执行步骤
     */
    public WorkflowStep getNextPendingStep(Long workflowRunId) {
        List<WorkflowStep> pendingSteps = stepRepository.findByWorkflowRunIdAndState(workflowRunId, "PENDING");
        return pendingSteps.isEmpty() ? null : pendingSteps.get(0);
    }
}
