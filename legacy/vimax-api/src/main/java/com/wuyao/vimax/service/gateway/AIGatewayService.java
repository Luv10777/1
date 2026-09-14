package com.wuyao.vimax.service.gateway;

import com.wuyao.vimax.config.APIKeyConfig;
import com.wuyao.vimax.entity.ProviderJob;
import com.wuyao.vimax.repository.ProviderJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI Gateway 服务
 *
 * 统一管理 FluAPI 和 ToAPIs 的调用
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AIGatewayService {

    private final ProviderJobRepository providerJobRepository;
    private final FluAPIAdapter fluAPIAdapter;
    private final ToAPIsAdapter toAPIsAdapter;
    private final APIKeyConfig apiKeyConfig;

    /**
     * 提交文本生成任务
     */
    @Transactional
    public ProviderJob submitTextGeneration(Long generationTaskId, String prompt,
                                           String modelCapability) {
        log.info("提交文本生成任务：taskId={}, capability={}", generationTaskId, modelCapability);

        // 根据 modelCapability 选择供应商
        String provider = selectProvider(modelCapability);

        ProviderJob job = new ProviderJob();
        job.setGenerationTaskId(generationTaskId);
        job.setProvider(provider);
        job.setJobType("TEXT_GENERATION");
        job.setModelCapability(modelCapability);

        if ("FLUAPI".equals(provider)) {
            // 使用真实的 API Key
            String apiKey = apiKeyConfig.getFluApiTextKey();
            String jobId = fluAPIAdapter.submitTextGeneration(prompt, modelCapability, apiKey);
            job.setProviderJobId(jobId);
        }

        ProviderJob saved = providerJobRepository.save(job);
        log.info("文本生成任务已提交：jobId={}, providerJobId={}", saved.getId(), saved.getProviderJobId());

        return saved;
    }

    /**
     * 提交图片生成任务
     */
    @Transactional
    public ProviderJob submitImageGeneration(Long generationTaskId, String prompt,
                                            String modelCapability) {
        log.info("提交图片生成任务：taskId={}, capability={}", generationTaskId, modelCapability);

        String provider = selectProvider(modelCapability);

        ProviderJob job = new ProviderJob();
        job.setGenerationTaskId(generationTaskId);
        job.setProvider(provider);
        job.setJobType("IMAGE_GENERATION");
        job.setModelCapability(modelCapability);

        if ("FLUAPI".equals(provider)) {
            // 使用真实的 API Key
            String apiKey = apiKeyConfig.getFluApiImageKey();
            String jobId = fluAPIAdapter.submitImageGeneration(prompt, modelCapability, apiKey);
            job.setProviderJobId(jobId);
        }

        ProviderJob saved = providerJobRepository.save(job);
        log.info("图片生成任务已提交：jobId={}, providerJobId={}", saved.getId(), saved.getProviderJobId());

        return saved;
    }

    /**
     * 提交视频生成任务
     */
    @Transactional
    public ProviderJob submitVideoGeneration(Long generationTaskId, String imageUrl,
                                            String prompt, String modelCapability) {
        log.info("提交视频生成任务：taskId={}, capability={}", generationTaskId, modelCapability);

        String provider = selectProvider(modelCapability);

        ProviderJob job = new ProviderJob();
        job.setGenerationTaskId(generationTaskId);
        job.setProvider(provider);
        job.setJobType("VIDEO_GENERATION");
        job.setModelCapability(modelCapability);

        if ("TOAPIS".equals(provider)) {
            // 使用真实的 API Key
            String apiKey = apiKeyConfig.getToApisKey();
            String jobId = toAPIsAdapter.submitVideoGeneration(imageUrl, prompt, modelCapability, apiKey);
            job.setProviderJobId(jobId);
        }

        ProviderJob saved = providerJobRepository.save(job);
        log.info("视频生成任务已提交：jobId={}, providerJobId={}", saved.getId(), saved.getProviderJobId());

        return saved;
    }

    /**
     * 定时检查待完成的任务（每 5 秒）
     */
    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void checkPendingJobs() {
        List<ProviderJob> pendingJobs = providerJobRepository.findPendingJobs();

        if (pendingJobs.isEmpty()) {
            return;
        }

        log.info("检查 {} 个待完成任务", pendingJobs.size());

        for (ProviderJob job : pendingJobs) {
            try {
                checkJobStatus(job);
            } catch (Exception e) {
                log.error("检查任务状态失败：jobId={}, error={}", job.getId(), e.getMessage());
            }
        }
    }

    /**
     * 检查单个任务状态
     */
    private void checkJobStatus(ProviderJob job) {
        log.debug("检查任务状态：jobId={}, provider={}, providerJobId={}",
                job.getId(), job.getProvider(), job.getProviderJobId());

        // 使用真实的 API Key
        String apiKey;
        if ("FLUAPI".equals(job.getProvider())) {
            apiKey = apiKeyConfig.getFluApiTextKey();
            fluAPIAdapter.checkJobStatus(job, apiKey);
        } else if ("TOAPIS".equals(job.getProvider())) {
            apiKey = apiKeyConfig.getToApisKey();
            toAPIsAdapter.checkJobStatus(job, apiKey);
        }

        job.setLastCheckedAt(LocalDateTime.now());
        providerJobRepository.save(job);
    }

    /**
     * 根据模型能力选择供应商
     */
    private String selectProvider(String modelCapability) {
        // 简化版：根据能力类型选择供应商
        // 实际应该从配置表中读取
        if (modelCapability.contains("VIDEO") || modelCapability.contains("SEEDANCE")) {
            return "TOAPIS";
        }
        return "FLUAPI";
    }

    /**
     * 查询任务状态
     */
    public ProviderJob getJobStatus(Long jobId) {
        return providerJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + jobId));
    }
}
