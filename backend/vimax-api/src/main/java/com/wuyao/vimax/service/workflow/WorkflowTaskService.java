package com.wuyao.vimax.service.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.vimax.entity.GenerationTask;
import com.wuyao.vimax.entity.WorkflowRun;
import com.wuyao.vimax.repository.GenerationTaskRepository;
import com.wuyao.vimax.service.gateway.AIGatewayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 工作流任务管理服务
 *
 * 负责创建和管理工作流中的生成任务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowTaskService {

    private final GenerationTaskRepository taskRepository;
    private final AIGatewayService aiGatewayService;
    private final ObjectMapper objectMapper;

    /**
     * 创建生成任务
     */
    @Transactional
    public GenerationTask createTask(Long videoProjectId, Integer stepNumber,
                                     String stepName, String stepType,
                                     Map<String, Object> inputData) {
        log.info("创建生成任务: step={}, type={}", stepName, stepType);

        try {
            GenerationTask task = new GenerationTask();
            task.setVideoProjectId(videoProjectId);
            task.setStepNumber(stepNumber);
            task.setStepName(stepName);
            task.setStepType(stepType);
            task.setInputData(objectMapper.writeValueAsString(inputData));
            task.setStatus("PENDING");

            // 设置人工审核标志
            if (stepName.contains("APPROVE") || stepName.contains("REVIEW") || stepName.contains("SELECT")) {
                task.setRequiresHumanReview(true);
            }

            GenerationTask saved = taskRepository.save(task);
            log.info("生成任务已创建: taskId={}", saved.getId());

            return saved;

        } catch (Exception e) {
            log.error("创建生成任务失败", e);
            throw new RuntimeException("创建任务失败: " + e.getMessage());
        }
    }

    /**
     * 提交文本生成任务
     */
    @Transactional
    public void submitTextGeneration(GenerationTask task, String prompt, String modelCapability) {
        log.info("提交文本生成: taskId={}", task.getId());

        try {
            task.setStatus("SUBMITTED");
            task.setStartedAt(LocalDateTime.now());
            taskRepository.save(task);

            // 调用 AI Gateway
            aiGatewayService.submitTextGeneration(task.getId(), prompt, modelCapability);

        } catch (Exception e) {
            log.error("提交文本生成失败", e);
            task.setStatus("FAILED");
            task.setErrorMessage(e.getMessage());
            taskRepository.save(task);
        }
    }

    /**
     * 提交图片生成任务
     */
    @Transactional
    public void submitImageGeneration(GenerationTask task, String prompt, String modelCapability) {
        log.info("提交图片生成: taskId={}", task.getId());

        try {
            task.setStatus("SUBMITTED");
            task.setStartedAt(LocalDateTime.now());
            taskRepository.save(task);

            // 调用 AI Gateway
            aiGatewayService.submitImageGeneration(task.getId(), prompt, modelCapability);

        } catch (Exception e) {
            log.error("提交图片生成失败", e);
            task.setStatus("FAILED");
            task.setErrorMessage(e.getMessage());
            taskRepository.save(task);
        }
    }

    /**
     * 提交视频生成任务
     */
    @Transactional
    public void submitVideoGeneration(GenerationTask task, String imageUrl, String prompt, String modelCapability) {
        log.info("提交视频生成: taskId={}", task.getId());

        try {
            task.setStatus("SUBMITTED");
            task.setStartedAt(LocalDateTime.now());
            taskRepository.save(task);

            // 调用 AI Gateway
            aiGatewayService.submitVideoGeneration(task.getId(), imageUrl, prompt, modelCapability);

        } catch (Exception e) {
            log.error("提交视频生成失败", e);
            task.setStatus("FAILED");
            task.setErrorMessage(e.getMessage());
            taskRepository.save(task);
        }
    }

    /**
     * 完成任务
     */
    @Transactional
    public void completeTask(Long taskId, Map<String, Object> outputData) {
        log.info("完成任务: taskId={}", taskId);

        try {
            GenerationTask task = taskRepository.findById(taskId)
                    .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));

            task.setStatus("COMPLETED");
            task.setOutputData(objectMapper.writeValueAsString(outputData));
            task.setCompletedAt(LocalDateTime.now());
            taskRepository.save(task);

        } catch (Exception e) {
            log.error("完成任务失败", e);
            throw new RuntimeException("完成任务失败: " + e.getMessage());
        }
    }

    /**
     * 任务失败
     */
    @Transactional
    public void failTask(Long taskId, String errorMessage) {
        log.error("任务失败: taskId={}, error={}", taskId, errorMessage);

        GenerationTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));

        task.setStatus("FAILED");
        task.setErrorMessage(errorMessage);
        taskRepository.save(task);
    }

    /**
     * 人工审核任务
     */
    @Transactional
    public void reviewTask(Long taskId, boolean approved, String comment, Long reviewerId) {
        log.info("人工审核任务: taskId={}, approved={}", taskId, approved);

        GenerationTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));

        task.setHumanReviewedAt(LocalDateTime.now());
        task.setHumanReviewedBy(reviewerId);
        task.setHumanReviewResult(approved ? "APPROVED" : "REJECTED");
        task.setHumanReviewComment(comment);

        if (approved) {
            task.setStatus("COMPLETED");
            task.setCompletedAt(LocalDateTime.now());
        } else {
            task.setStatus("REJECTED");
        }

        taskRepository.save(task);
    }
}
