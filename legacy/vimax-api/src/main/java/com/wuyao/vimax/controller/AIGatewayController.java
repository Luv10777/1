package com.wuyao.vimax.controller;

import com.wuyao.vimax.dto.ApiResponse;
import com.wuyao.vimax.dto.SubmitAITaskRequest;
import com.wuyao.vimax.entity.ProviderJob;
import com.wuyao.vimax.service.gateway.AIGatewayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * AI Gateway Controller
 *
 * 统一的 AI 调用入口
 */
@RestController
@RequestMapping("/ai-gateway")
@RequiredArgsConstructor
@Slf4j
public class AIGatewayController {

    private final AIGatewayService aiGatewayService;

    /**
     * 提交文本生成任务
     */
    @PostMapping("/text-generation")
    public ApiResponse<ProviderJob> submitTextGeneration(@RequestBody SubmitAITaskRequest request) {
        log.info("提交文本生成：taskId={}", request.getGenerationTaskId());

        ProviderJob job = aiGatewayService.submitTextGeneration(
                request.getGenerationTaskId(),
                request.getPrompt(),
                request.getModelCapability()
        );

        return ApiResponse.success("文本生成任务已提交", job);
    }

    /**
     * 提交图片生成任务
     */
    @PostMapping("/image-generation")
    public ApiResponse<ProviderJob> submitImageGeneration(@RequestBody SubmitAITaskRequest request) {
        log.info("提交图片生成：taskId={}", request.getGenerationTaskId());

        ProviderJob job = aiGatewayService.submitImageGeneration(
                request.getGenerationTaskId(),
                request.getPrompt(),
                request.getModelCapability()
        );

        return ApiResponse.success("图片生成任务已提交", job);
    }

    /**
     * 提交视频生成任务
     */
    @PostMapping("/video-generation")
    public ApiResponse<ProviderJob> submitVideoGeneration(@RequestBody SubmitAITaskRequest request) {
        log.info("提交视频生成：taskId={}", request.getGenerationTaskId());

        ProviderJob job = aiGatewayService.submitVideoGeneration(
                request.getGenerationTaskId(),
                request.getImageUrl(),
                request.getPrompt(),
                request.getModelCapability()
        );

        return ApiResponse.success("视频生成任务已提交", job);
    }

    /**
     * 查询任务状态
     */
    @GetMapping("/jobs/{jobId}")
    public ApiResponse<ProviderJob> getJobStatus(@PathVariable Long jobId) {
        log.info("查询任务状态：jobId={}", jobId);

        ProviderJob job = aiGatewayService.getJobStatus(jobId);
        return ApiResponse.success(job);
    }
}
