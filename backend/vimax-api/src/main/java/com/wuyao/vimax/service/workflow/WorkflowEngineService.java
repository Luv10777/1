package com.wuyao.vimax.service.workflow;

import com.wuyao.vimax.entity.WorkflowRun;
import com.wuyao.vimax.entity.WorkflowStep;
import com.wuyao.vimax.repository.WorkflowRunRepository;
import com.wuyao.vimax.repository.WorkflowStepRepository;
import com.wuyao.vimax.service.task.TextUnderstandingService;
import com.wuyao.vimax.service.task.ImageGenerationService;
import com.wuyao.vimax.service.task.VideoGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工作流引擎服务
 *
 * Phase 3: 完整工作流编排和执行
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowEngineService {

    private final WorkflowRunRepository runRepository;
    private final WorkflowStepRepository stepRepository;
    private final WorkflowStepManagementService stepManagementService;
    private final TextUnderstandingService textService;
    private final ImageGenerationService imageService;
    private final VideoGenerationService videoService;

    /**
     * 启动工作流
     */
    @Transactional
    public void startWorkflow(Long workflowRunId) {
        log.info("启动工作流: runId={}", workflowRunId);

        WorkflowRun run = runRepository.findById(workflowRunId)
            .orElseThrow(() -> new IllegalArgumentException("工作流不存在"));

        // 初始化步骤
        stepManagementService.initializeSteps(workflowRunId);

        // 更新运行状态
        run.setState("RUNNING");
        run.setStartedAt(LocalDateTime.now());
        runRepository.save(run);

        // 执行第一个步骤
        executeNextStep(workflowRunId);
    }

    /**
     * 执行下一个步骤
     */
    @Transactional
    public void executeNextStep(Long workflowRunId) {
        log.info("执行下一步: runId={}", workflowRunId);

        WorkflowStep nextStep = stepManagementService.getNextPendingStep(workflowRunId);

        if (nextStep == null) {
            log.info("工作流已完成所有步骤: runId={}", workflowRunId);
            completeWorkflow(workflowRunId);
            return;
        }

        // 检查依赖
        if (nextStep.getDependsOnStepId() != null) {
            WorkflowStep dependsOn = stepRepository.findById(nextStep.getDependsOnStepId()).orElse(null);
            if (dependsOn == null || !"COMPLETED".equals(dependsOn.getState())) {
                log.warn("依赖步骤未完成: stepId={}, dependsOn={}", nextStep.getId(), nextStep.getDependsOnStepId());
                return;
            }
        }

        // 执行步骤
        executeStep(nextStep);
    }

    /**
     * 执行单个步骤
     */
    @Transactional
    public void executeStep(WorkflowStep step) {
        log.info("执行步骤: stepId={}, code={}, type={}", step.getId(), step.getStepCode(), step.getStepType());

        try {
            stepManagementService.startStep(step.getId());

            switch (step.getStepType()) {
                case "VALIDATION" -> executeValidation(step);
                case "AI_GENERATION" -> executeAIGeneration(step);
                case "HUMAN_REVIEW" -> executeHumanReview(step);
                case "FINALIZATION" -> executeFinalization(step);
                default -> throw new IllegalArgumentException("未知步骤类型: " + step.getStepType());
            }

        } catch (Exception e) {
            log.error("步骤执行失败: stepId={}", step.getId(), e);
            handleStepFailure(step, e);
        }
    }

    /**
     * 执行验证步骤
     */
    private void executeValidation(WorkflowStep step) {
        log.info("执行验证: stepCode={}", step.getStepCode());

        // TODO: 实现具体验证逻辑
        if ("VALIDATE_INPUT".equals(step.getStepCode())) {
            // 验证输入完整性
            stepManagementService.completeStep(step.getId(), "{\"validated\": true}");
            executeNextStep(step.getWorkflowRunId());
        } else if ("QUALITY_CHECK".equals(step.getStepCode())) {
            // 技术质量检查
            stepManagementService.completeStep(step.getId(), "{\"quality_score\": 95}");
            executeNextStep(step.getWorkflowRunId());
        }
    }

    /**
     * 执行AI生成步骤
     */
    private void executeAIGeneration(WorkflowStep step) {
        log.info("执行AI生成: stepCode={}", step.getStepCode());

        switch (step.getStepCode()) {
            case "TEXT_UNDERSTANDING" -> {
                // TODO: 从输入获取prompt
                String prompt = "生成视频脚本"; // 临时
                textService.submitTextTask(step.getWorkflowRunId(), prompt);
                // 注意：异步任务完成后需要回调
            }
            case "IMAGE_GENERATION" -> {
                String prompt = "生成首帧图"; // 临时
                imageService.submitImageTask(step.getWorkflowRunId(), prompt, "1024x1024", "standard");
            }
            case "VIDEO_GENERATION" -> {
                String imageUrl = "https://example.com/image.jpg"; // 临时
                String prompt = "生成视频"; // 临时
                videoService.submitVideoTask(step.getWorkflowRunId(), imageUrl, prompt, 5);
            }
        }
    }

    /**
     * 执行人工审核步骤（等待人工操作）
     */
    private void executeHumanReview(WorkflowStep step) {
        log.info("等待人工审核: stepCode={}", step.getStepCode());
        // 人工审核步骤不自动完成，等待外部调用 reviewStep
    }

    /**
     * 执行完成步骤
     */
    private void executeFinalization(WorkflowStep step) {
        log.info("执行完成: stepCode={}", step.getStepCode());

        if ("FINALIZE".equals(step.getStepCode())) {
            // 完成入库
            stepManagementService.completeStep(step.getId(), "{\"finalized\": true}");
            completeWorkflow(step.getWorkflowRunId());
        }
    }

    /**
     * 处理步骤失败
     */
    @Transactional
    public void handleStepFailure(WorkflowStep step, Exception e) {
        log.error("处理步骤失败: stepId={}, error={}", step.getId(), e.getMessage());

        if (step.getRetryCount() < step.getMaxRetries()) {
            // 重试
            log.info("准备重试步骤: stepId={}, retryCount={}", step.getId(), step.getRetryCount() + 1);
            stepManagementService.failStep(step.getId(), e.getMessage());
            // TODO: 延迟重试
        } else {
            // 超过最大重试次数，标记工作流失败
            stepManagementService.failStep(step.getId(), e.getMessage());
            failWorkflow(step.getWorkflowRunId(), "步骤失败超过最大重试次数");
        }
    }

    /**
     * 完成工作流
     */
    @Transactional
    public void completeWorkflow(Long workflowRunId) {
        log.info("完成工作流: runId={}", workflowRunId);

        WorkflowRun run = runRepository.findById(workflowRunId)
            .orElseThrow(() -> new IllegalArgumentException("工作流不存在"));

        run.setState("COMPLETED");
        run.setCompletedAt(LocalDateTime.now());
        runRepository.save(run);
    }

    /**
     * 工作流失败
     */
    @Transactional
    public void failWorkflow(Long workflowRunId, String errorMessage) {
        log.error("工作流失败: runId={}, error={}", workflowRunId, errorMessage);

        WorkflowRun run = runRepository.findById(workflowRunId)
            .orElseThrow(() -> new IllegalArgumentException("工作流不存在"));

        run.setState("FAILED");
        run.setFailedAt(LocalDateTime.now());
        run.setErrorMessage(errorMessage);
        runRepository.save(run);
    }

    /**
     * 取消工作流
     */
    @Transactional
    public void cancelWorkflow(Long workflowRunId) {
        log.info("取消工作流: runId={}", workflowRunId);

        WorkflowRun run = runRepository.findById(workflowRunId)
            .orElseThrow(() -> new IllegalArgumentException("工作流不存在"));

        run.setState("CANCELLED");
        run.setCancelledAt(LocalDateTime.now());
        runRepository.save(run);
    }
}
