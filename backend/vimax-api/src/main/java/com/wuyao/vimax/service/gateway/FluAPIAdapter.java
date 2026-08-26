package com.wuyao.vimax.service.gateway;

import com.wuyao.vimax.entity.ProviderJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * FluAPI 适配器
 *
 * 封装 FluAPI 的文本和图片生成接口
 */
@Component
@Slf4j
public class FluAPIAdapter {

    /**
     * 提交文本生成任务
     */
    public String submitTextGeneration(String prompt, String modelCapability) {
        log.info("FluAPI 提交文本生成：prompt={}, capability={}", prompt, modelCapability);

        // TODO: 实际调用 FluAPI
        // 1. 读取 API 密钥
        // 2. 构造请求
        // 3. 发送 HTTP POST
        // 4. 解析响应获取 jobId

        String mockJobId = "flu_text_" + System.currentTimeMillis();
        log.info("FluAPI 任务已提交：jobId={}", mockJobId);

        return mockJobId;
    }

    /**
     * 提交图片生成任务
     */
    public String submitImageGeneration(String prompt, String modelCapability) {
        log.info("FluAPI 提交图片生成：prompt={}, capability={}", prompt, modelCapability);

        // TODO: 实际调用 FluAPI Image 2.0

        String mockJobId = "flu_img_" + System.currentTimeMillis();
        log.info("FluAPI 任务已提交：jobId={}", mockJobId);

        return mockJobId;
    }

    /**
     * 检查任务状态
     */
    public void checkJobStatus(ProviderJob job) {
        log.debug("FluAPI 检查任务状态：providerJobId={}", job.getProviderJobId());

        // TODO: 实际调用 FluAPI 查询接口
        // 1. 根据 providerJobId 查询
        // 2. 更新 job 的 status, progress, resultUrl 等
        // 3. 如果完成，设置 completedAt
        // 4. 如果失败，设置 failedAt 和 errorMessage

        // Mock: 模拟任务完成
        if (job.getProgress() >= 100) {
            job.setStatus("COMPLETED");
            job.setResultUrl("https://example.com/result/" + job.getProviderJobId());
        } else {
            job.setProgress(job.getProgress() + 10);
        }
    }
}
