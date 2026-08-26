package com.wuyao.vimax.service.gateway;

import com.wuyao.vimax.entity.ProviderJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ToAPIs (Seedance) 适配器
 *
 * 封装 ToAPIs Seedance 2.0/2.5 的视频生成接口
 */
@Component
@Slf4j
public class ToAPIsAdapter {

    /**
     * 提交视频生成任务
     */
    public String submitVideoGeneration(String imageUrl, String prompt, String modelCapability) {
        log.info("ToAPIs 提交视频生成：imageUrl={}, prompt={}, capability={}",
                imageUrl, prompt, modelCapability);

        // TODO: 实际调用 ToAPIs Seedance API
        // 1. 读取 API 密钥
        // 2. 构造请求（image_url, prompt_text, model_version）
        // 3. 发送 HTTP POST 到 /v2/video/generation/create
        // 4. 解析响应获取 task_id

        String mockJobId = "toa_vid_" + System.currentTimeMillis();
        log.info("ToAPIs 任务已提交：jobId={}", mockJobId);

        return mockJobId;
    }

    /**
     * 检查任务状态
     */
    public void checkJobStatus(ProviderJob job) {
        log.debug("ToAPIs 检查任务状态：providerJobId={}", job.getProviderJobId());

        // TODO: 实际调用 ToAPIs 查询接口
        // 1. 调用 /v2/video/generation/query?task_id=xxx
        // 2. 解析响应：status (pending/processing/succeeded/failed)
        // 3. 更新 job 的状态和进度
        // 4. 如果 succeeded，获取 video_url
        // 5. 如果 failed，记录 error_message

        // Mock: 模拟异步任务流程
        String currentStatus = job.getStatus();

        if ("SUBMITTED".equals(currentStatus)) {
            job.setStatus("QUEUED");
        } else if ("QUEUED".equals(currentStatus)) {
            job.setStatus("PROCESSING");
            job.setProgress(20);
        } else if ("PROCESSING".equals(currentStatus)) {
            int newProgress = Math.min(job.getProgress() + 15, 100);
            job.setProgress(newProgress);

            if (newProgress >= 100) {
                job.setStatus("COMPLETED");
                job.setResultUrl("https://example.com/video/" + job.getProviderJobId() + ".mp4");
            }
        }
    }
}
