package com.wuyao.vimax.service.task;

import com.wuyao.vimax.entity.GenerationTask;
import com.wuyao.vimax.repository.GenerationTaskRepository;
import com.wuyao.vimax.service.gateway.adapter.FluAPITextAdapter;
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
 * 文本理解任务服务
 *
 * 使用 FluAPI gpt5.6-luna 生成视频脚本
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TextUnderstandingService {

    private final GenerationTaskRepository taskRepository;
    private final FluAPITextAdapter textAdapter;
    private final APIKeyConfig apiKeyConfig;

    /**
     * 提交文本生成任务
     *
     * @param workflowRunId 工作流运行ID
     * @param prompt 提示词
     * @return GenerationTask
     */
    @Transactional
    public GenerationTask submitTextTask(Long workflowRunId, String prompt) {
        log.info("提交文本生成任务: workflowRunId={}", workflowRunId);

        // 计算输入哈希（幂等性）
        String inputHash = calculateHash(prompt);

        // 检查是否已存在相同任务
        GenerationTask existingTask = taskRepository.findByInputHash(inputHash).orElse(null);
        if (existingTask != null && "COMPLETED".equals(existingTask.getStatus())) {
            log.info("复用已完成的文本任务: taskId={}", existingTask.getId());
            return existingTask;
        }

        // 创建任务记录
        GenerationTask task = new GenerationTask();
        task.setWorkflowRunId(workflowRunId);
        task.setTaskType("TEXT");
        task.setModelCapability("TEXT_UNDERSTANDING");
        task.setInputHash(inputHash);
        task.setIdempotencyKey(UUID.randomUUID().toString());
        task.setStatus("PENDING");

        GenerationTask saved = taskRepository.save(task);
        log.info("文本任务已创建: taskId={}", saved.getId());

        // 异步执行任务
        executeTextTask(saved.getId(), prompt);

        return saved;
    }

    /**
     * 执行文本生成
     */
    @Transactional
    public void executeTextTask(Long taskId, String prompt) {
        log.info("执行文本生成: taskId={}", taskId);

        GenerationTask task = taskRepository.findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));

        try {
            // 更新状态为处理中
            task.setStatus("PROCESSING");
            taskRepository.save(task);

            // 构建请求
            ProviderTaskRequest request = ProviderTaskRequest.builder()
                .taskType("TEXT")
                .prompt(prompt)
                .modelCapability("TEXT_UNDERSTANDING")
                .build();

            // 调用 FluAPI
            String apiKey = apiKeyConfig.getFluApiTextKey();
            ProviderTaskResponse response = textAdapter.submitTask(request, apiKey);

            if ("COMPLETED".equals(response.getStatus())) {
                // 成功
                task.setStatus("COMPLETED");
                task.setResultRef(response.getResultUrl()); // 文本内容
                task.setProviderJobId(response.getProviderJobId());
                task.setActualCost(BigDecimal.valueOf(0.002)); // 估算成本
                task.setCompletedAt(response.getCompletedAt());

                log.info("文本生成成功: taskId={}, length={}", taskId, response.getResultUrl().length());

            } else {
                // 失败
                task.setStatus("FAILED");
                log.error("文本生成失败: taskId={}, error={}", taskId, response.getErrorMessage());
            }

            taskRepository.save(task);

        } catch (Exception e) {
            log.error("文本生成异常: taskId={}", taskId, e);
            task.setStatus("FAILED");
            taskRepository.save(task);
        }
    }

    /**
     * 构建视频脚本提示词
     */
    public String buildScriptPrompt(String merchantName, String productDescription, String targetPlatform) {
        return String.format("""
            你是一个专业的短视频脚本创作者。请根据以下信息创建一个吸引人的视频脚本：

            商家名称：%s
            产品描述：%s
            目标平台：%s

            要求：
            1. 视频时长：5-10秒
            2. 开头抓眼球，结尾留悬念
            3. 突出产品核心卖点
            4. 符合平台调性

            请直接输出脚本内容，格式：
            【场景】描述画面
            【文案】口播内容
            """, merchantName, productDescription, targetPlatform);
    }

    /**
     * 计算输入哈希
     */
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
