package com.wuyao.vimax.service.workflow;

import com.wuyao.vimax.entity.GenerationTask;
import com.wuyao.vimax.repository.GenerationTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 工作流任务管理服务
 *
 * TODO: 需要完整实现任务创建和管理逻辑
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowTaskService {

    private final GenerationTaskRepository taskRepository;

    /**
     * 创建生成任务
     */
    @Transactional
    public GenerationTask createTask(Long workflowRunId, String taskType, String modelCapability) {
        log.info("创建生成任务: runId={}, type={}", workflowRunId, taskType);

        GenerationTask task = new GenerationTask();
        task.setWorkflowRunId(workflowRunId);
        task.setTaskType(taskType);
        task.setModelCapability(modelCapability);
        task.setInputHash(UUID.randomUUID().toString());
        task.setIdempotencyKey(UUID.randomUUID().toString());
        task.setStatus("PENDING");

        GenerationTask saved = taskRepository.save(task);
        log.info("生成任务已创建: taskId={}", saved.getId());

        return saved;
    }

    /**
     * 完成任务
     */
    @Transactional
    public void completeTask(Long taskId, String resultRef) {
        log.info("完成任务: taskId={}", taskId);

        GenerationTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));

        task.setStatus("COMPLETED");
        task.setResultRef(resultRef);
        task.setCompletedAt(LocalDateTime.now());
        taskRepository.save(task);
    }

    /**
     * 任务失败
     */
    @Transactional
    public void failTask(Long taskId, String errorMessage) {
        log.info("任务失败: taskId={}", taskId);

        GenerationTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));

        task.setStatus("FAILED");
        taskRepository.save(task);
    }
}
