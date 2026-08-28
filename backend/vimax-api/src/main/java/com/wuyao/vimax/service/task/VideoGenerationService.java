package com.wuyao.vimax.service.task;

import com.wuyao.vimax.entity.GenerationTask;
import com.wuyao.vimax.entity.ProviderJob;
import com.wuyao.vimax.repository.GenerationTaskRepository;
import com.wuyao.vimax.repository.ProviderJobRepository;
import com.wuyao.vimax.service.gateway.adapter.ToAPIsVideoAdapter;
import com.wuyao.vimax.service.gateway.adapter.ProviderTaskRequest;
import com.wuyao.vimax.service.gateway.adapter.ProviderTaskResponse;
import com.wuyao.vimax.config.APIKeyConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 视频生成任务服务
 *
 * Phase 1.7: 图生视频任务和Provider状态轮询
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoGenerationService {

    private final GenerationTaskRepository taskRepository;
    private final ProviderJobRepository providerJobRepository;
    private final ToAPIsVideoAdapter videoAdapter;
    private final APIKeyConfig apiKeyConfig;

    @Transactional
    public GenerationTask submitVideoTask(Long workflowRunId, String imageUrl, String prompt, Integer duration) {
        log.info("提交视频生成任务: workflowRunId={}, imageUrl={}", workflowRunId, imageUrl);

        String inputHash = calculateHash(imageUrl + prompt + duration);

        GenerationTask existingTask = taskRepository.findByInputHash(inputHash).orElse(null);
        if (existingTask != null && "COMPLETED".equals(existingTask.getStatus())) {
            log.info("复用已完成的视频任务: taskId={}", existingTask.getId());
            return existingTask;
        }

        GenerationTask task = new GenerationTask();
        task.setWorkflowRunId(workflowRunId);
        task.setTaskType("VIDEO");
        task.setModelCapability("VIDEO_GENERATION");
        task.setInputHash(inputHash);
        task.setIdempotencyKey(UUID.randomUUID().toString());
        task.setStatus("PENDING");

        GenerationTask saved = taskRepository.save(task);
        log.info("视频任务已创建: taskId={}", saved.getId());

        executeVideoTask(saved.getId(), imageUrl, prompt, duration);

        return saved;
    }

    @Transactional
    public void executeVideoTask(Long taskId, String imageUrl, String prompt, Integer duration) {
        log.info("执行视频生成: taskId={}", taskId);

        GenerationTask task = taskRepository.findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));

        try {
            task.setStatus("PROCESSING");
            taskRepository.save(task);

            ProviderTaskRequest request = ProviderTaskRequest.builder()
                .taskType("VIDEO")
                .prompt(prompt)
                .imageUrl(imageUrl)
                .duration(duration != null ? duration : 5)
                .aspectRatio("9:16")
                .modelCapability("VIDEO_GENERATION")
                .build();

            String apiKey = apiKeyConfig.getToApisKey();
            ProviderTaskResponse response = videoAdapter.submitTask(request, apiKey);

            if ("PENDING".equals(response.getStatus()) || "PROCESSING".equals(response.getStatus())) {
                // 异步任务已提交
                task.setProviderJobId(response.getProviderJobId());
                task.setStatus("PROCESSING");

                // 创建 ProviderJob 记录
                createProviderJob(task.getId(), response.getProviderJobId());

                log.info("视频任务已提交到Provider: taskId={}, providerJobId={}", taskId, response.getProviderJobId());
            } else if ("FAILED".equals(response.getStatus())) {
                task.setStatus("FAILED");
                log.error("视频生成失败: taskId={}, error={}", taskId, response.getErrorMessage());
            }

            taskRepository.save(task);

        } catch (Exception e) {
            log.error("视频生成异常: taskId={}", taskId, e);
            task.setStatus("FAILED");
            taskRepository.save(task);
        }
    }

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void pollProviderJobs() {
        List<ProviderJob> pendingJobs = providerJobRepository.findByStatusIn(List.of("SUBMITTED", "PROCESSING"));

        if (pendingJobs.isEmpty()) {
            return;
        }

        log.debug("轮询 {} 个待处理任务", pendingJobs.size());

        for (ProviderJob job : pendingJobs) {
            try {
                checkProviderJobStatus(job);
            } catch (Exception e) {
                log.error("轮询任务失败: jobId={}", job.getId(), e);
            }
        }
    }

    @Transactional
    public void checkProviderJobStatus(ProviderJob job) {
        log.debug("检查Provider任务状态: jobId={}, providerJobId={}", job.getId(), job.getProviderJobId());

        try {
            String apiKey = apiKeyConfig.getToApisKey();
            ProviderTaskResponse response = videoAdapter.checkTaskStatus(job.getProviderJobId(), apiKey);

            job.setStatus(response.getStatus());
            job.setProgress(response.getProgress());
            job.setLastCheckedAt(LocalDateTime.now());

            if ("COMPLETED".equals(response.getStatus())) {
                job.setCompletedAt(LocalDateTime.now());

                // 更新 GenerationTask
                GenerationTask task = taskRepository.findById(job.getGenerationTaskId())
                    .orElseThrow(() -> new IllegalArgumentException("任务不存在"));

                task.setStatus("COMPLETED");
                task.setResultRef(response.getResultUrl());
                task.setActualCost(BigDecimal.valueOf(0.20));
                task.setCompletedAt(LocalDateTime.now());

                taskRepository.save(task);

                log.info("视频生成完成: taskId={}, url={}", task.getId(), response.getResultUrl());

            } else if ("FAILED".equals(response.getStatus())) {
                job.setFailedAt(LocalDateTime.now());
                job.setErrorCode(response.getErrorCode());
                job.setErrorMessage(response.getErrorMessage());

                GenerationTask task = taskRepository.findById(job.getGenerationTaskId())
                    .orElseThrow(() -> new IllegalArgumentException("任务不存在"));

                task.setStatus("FAILED");
                taskRepository.save(task);

                log.error("视频生成失败: taskId={}, error={}", task.getId(), response.getErrorMessage());
            }

            providerJobRepository.save(job);

        } catch (Exception e) {
            log.error("检查Provider状态失败: jobId={}", job.getId(), e);
        }
    }

    private void createProviderJob(Long generationTaskId, String providerJobId) {
        ProviderJob job = new ProviderJob();
        job.setGenerationTaskId(generationTaskId);
        job.setProvider("TOAPIS");
        job.setProviderJobId(providerJobId);
        job.setJobType("VIDEO_GENERATION");
        job.setModelCapability("SEEDANCE_2");
        job.setStatus("SUBMITTED");
        job.setProgress(0);

        providerJobRepository.save(job);
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
