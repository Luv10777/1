package com.wuyao.vimax.service.task;

import com.wuyao.vimax.entity.GenerationTask;
import com.wuyao.vimax.repository.GenerationTaskRepository;
import com.wuyao.vimax.service.gateway.adapter.FluAPIImageAdapter;
import com.wuyao.vimax.service.gateway.adapter.ProviderTaskRequest;
import com.wuyao.vimax.service.gateway.adapter.ProviderTaskResponse;
import com.wuyao.vimax.config.APIKeyConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * 图片生成任务服务
 *
 * Phase 1.6: 首帧图生成任务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImageGenerationService {

    private final GenerationTaskRepository taskRepository;
    private final FluAPIImageAdapter imageAdapter;
    private final APIKeyConfig apiKeyConfig;

    @Transactional
    public GenerationTask submitImageTask(Long workflowRunId, String prompt, String size, String quality) {
        log.info("提交图片生成任务: workflowRunId={}", workflowRunId);

        String inputHash = calculateHash(prompt + size + quality);

        GenerationTask existingTask = taskRepository.findByInputHash(inputHash).orElse(null);
        if (existingTask != null && "COMPLETED".equals(existingTask.getStatus())) {
            log.info("复用已完成的图片任务: taskId={}", existingTask.getId());
            return existingTask;
        }

        GenerationTask task = new GenerationTask();
        task.setWorkflowRunId(workflowRunId);
        task.setTaskType("IMAGE");
        task.setModelCapability("IMAGE_GENERATION");
        task.setInputHash(inputHash);
        task.setIdempotencyKey(UUID.randomUUID().toString());
        task.setStatus("PENDING");

        GenerationTask saved = taskRepository.save(task);
        log.info("图片任务已创建: taskId={}", saved.getId());

        executeImageTask(saved.getId(), prompt, size, quality);

        return saved;
    }

    @Transactional
    public void executeImageTask(Long taskId, String prompt, String size, String quality) {
        log.info("执行图片生成: taskId={}", taskId);

        GenerationTask task = taskRepository.findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));

        try {
            task.setStatus("PROCESSING");
            taskRepository.save(task);

            ProviderTaskRequest request = ProviderTaskRequest.builder()
                .taskType("IMAGE")
                .prompt(prompt)
                .size(size != null ? size : "1024x1024")
                .quality(quality != null ? quality : "standard")
                .style("vivid")
                .modelCapability("IMAGE_GENERATION")
                .build();

            String apiKey = apiKeyConfig.getFluApiImageKey();
            ProviderTaskResponse response = imageAdapter.submitTask(request, apiKey);

            if ("COMPLETED".equals(response.getStatus())) {
                task.setStatus("COMPLETED");
                task.setResultRef(response.getResultUrl());
                task.setProviderJobId(response.getProviderJobId());
                task.setActualCost(BigDecimal.valueOf(0.04));
                task.setCompletedAt(response.getCompletedAt());

                log.info("图片生成成功: taskId={}, url={}", taskId, response.getResultUrl());
            } else {
                task.setStatus("FAILED");
                log.error("图片生成失败: taskId={}, error={}", taskId, response.getErrorMessage());
            }

            taskRepository.save(task);

        } catch (Exception e) {
            log.error("图片生成异常: taskId={}", taskId, e);
            task.setStatus("FAILED");
            taskRepository.save(task);
        }
    }

    private String calculateHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("计算哈希失败", e);
        }
    }
}
