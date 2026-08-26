package com.wuyao.vimax.service.workflow;

import com.wuyao.vimax.entity.WorkflowRun;
import com.wuyao.vimax.entity.VideoProject;
import com.wuyao.vimax.repository.WorkflowRunRepository;
import com.wuyao.vimax.repository.VideoProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 工作流引擎服务
 *
 * 负责视频生成工作流的编排和执行
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowEngineService {

    private final VideoProjectRepository videoProjectRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowStepExecutor stepExecutor;

    /**
     * 启动工作流
     */
    @Transactional
    public WorkflowRun startWorkflow(Long videoProjectId) {
        log.info("启动工作流：videoProjectId={}", videoProjectId);

        // 查询视频项目
        VideoProject project = videoProjectRepository.findById(videoProjectId)
                .orElseThrow(() -> new IllegalArgumentException("视频项目不存在: " + videoProjectId));

        // 检查项目状态
        if (!"DRAFT".equals(project.getStatus())) {
            throw new IllegalStateException("项目状态不允许启动工作流: " + project.getStatus());
        }

        // 创建工作流运行记录
        WorkflowRun run = new WorkflowRun();
        run.setVideoProjectId(videoProjectId);
        run.setRunId(UUID.randomUUID().toString().replace("-", ""));
        run.setStatus("RUNNING");
        run.setCurrentStepName("VALIDATE_INPUT");

        WorkflowRun saved = workflowRunRepository.save(run);

        // 更新项目状态
        project.setStatus("VALIDATING");
        videoProjectRepository.save(project);

        log.info("工作流已启动：runId={}", saved.getRunId());

        // 异步执行第一步
        executeNextStep(saved);

        return saved;
    }

    /**
     * 执行下一步
     */
    @Transactional
    public void executeNextStep(WorkflowRun run) {
        String currentStep = run.getCurrentStepName();
        log.info("执行工作流步骤：runId={}, step={}", run.getRunId(), currentStep);

        try {
            // 执行当前步骤
            String nextStep = stepExecutor.executeStep(run, currentStep);

            if (nextStep == null) {
                // 工作流完成
                completeWorkflow(run);
            } else if ("WAITING_HUMAN_REVIEW".equals(nextStep)) {
                // 暂停等待人工审核
                pauseForHumanReview(run);
            } else {
                // 继续下一步
                run.setCurrentStepName(nextStep);
                workflowRunRepository.save(run);
                executeNextStep(run);
            }

        } catch (Exception e) {
            log.error("工作流步骤执行失败", e);
            failWorkflow(run, e.getMessage());
        }
    }

    /**
     * 暂停等待人工审核
     */
    @Transactional
    public void pauseForHumanReview(WorkflowRun run) {
        log.info("工作流暂停等待人工审核：runId={}", run.getRunId());

        run.setPausedForHumanReview(true);
        workflowRunRepository.save(run);

        // 更新项目状态
        VideoProject project = videoProjectRepository.findById(run.getVideoProjectId())
                .orElseThrow();
        project.setStatus("WAITING_" + run.getCurrentStepName() + "_APPROVAL");
        videoProjectRepository.save(project);
    }

    /**
     * 人工审核后继续
     */
    @Transactional
    public void resumeAfterHumanReview(Long runId, boolean approved, String comment) {
        log.info("人工审核完成：runId={}, approved={}", runId, approved);

        WorkflowRun run = workflowRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("工作流不存在: " + runId));

        if (!approved) {
            // 审核不通过，回退到上一步
            failWorkflow(run, "人工审核不通过: " + comment);
            return;
        }

        // 继续执行
        run.setPausedForHumanReview(false);
        workflowRunRepository.save(run);

        executeNextStep(run);
    }

    /**
     * 完成工作流
     */
    @Transactional
    public void completeWorkflow(WorkflowRun run) {
        log.info("工作流完成：runId={}", run.getRunId());

        run.setStatus("COMPLETED");
        run.setCompletedAt(LocalDateTime.now());
        workflowRunRepository.save(run);

        // 更新项目状态
        VideoProject project = videoProjectRepository.findById(run.getVideoProjectId())
                .orElseThrow();
        project.setStatus("COMPLETED");
        project.setCompletedAt(LocalDateTime.now());
        videoProjectRepository.save(project);
    }

    /**
     * 工作流失败
     */
    @Transactional
    public void failWorkflow(WorkflowRun run, String errorMessage) {
        log.error("工作流失败：runId={}, error={}", run.getRunId(), errorMessage);

        run.setStatus("FAILED");
        run.setErrorMessage(errorMessage);
        run.setFailedAt(LocalDateTime.now());
        workflowRunRepository.save(run);

        // 更新项目状态
        VideoProject project = videoProjectRepository.findById(run.getVideoProjectId())
                .orElseThrow();
        project.setStatus("FAILED");
        videoProjectRepository.save(project);
    }

    /**
     * 查询工作流状态
     */
    public WorkflowRun getWorkflowStatus(String runId) {
        return workflowRunRepository.findByRunId(runId)
                .orElseThrow(() -> new IllegalArgumentException("工作流不存在: " + runId));
    }

    /**
     * 查询待审核列表
     */
    public List<WorkflowRun> listPendingReview() {
        return workflowRunRepository.findPendingHumanReview();
    }
}
