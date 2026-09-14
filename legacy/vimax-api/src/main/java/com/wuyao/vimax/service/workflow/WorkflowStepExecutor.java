package com.wuyao.vimax.service.workflow;

import com.wuyao.vimax.entity.WorkflowRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 工作流步骤执行器
 *
 * 执行具体的工作流步骤逻辑
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowStepExecutor {

    /**
     * 执行工作流步骤
     *
     * @return 下一步名称，null 表示工作流完成
     */
    public String executeStep(WorkflowRun run, String stepName) {
        log.info("执行步骤：{}", stepName);

        switch (stepName) {
            case "VALIDATE_INPUT":
                return executeValidateInput(run);
            case "CAPTURE_MERCHANT_SNAPSHOT":
                return executeCaptureSnapshot(run);
            case "GENERATE_CREATIVE":
                return executeGenerateCreative(run);
            case "HUMAN_APPROVE_CREATIVE":
                return "WAITING_HUMAN_REVIEW";
            case "GENERATE_SCRIPT":
                return executeGenerateScript(run);
            case "HUMAN_APPROVE_SCRIPT":
                return "WAITING_HUMAN_REVIEW";
            case "GENERATE_STORYBOARD":
                return executeGenerateStoryboard(run);
            case "HUMAN_APPROVE_STORYBOARD":
                return "WAITING_HUMAN_REVIEW";
            case "GENERATE_REFERENCE_IMAGES":
                return executeGenerateReferenceImages(run);
            case "SELECT_REFERENCE_IMAGES":
                return "WAITING_HUMAN_REVIEW";
            case "GENERATE_SHOTS":
                return executeGenerateShots(run);
            case "CHECK_SHOTS":
                return executeCheckShots(run);
            case "COMPOSE_VIDEO":
                return executeComposeVideo(run);
            case "FINAL_QA":
                return executeFinalQA(run);
            case "HUMAN_REVIEW":
                return "WAITING_HUMAN_REVIEW";
            case "PUBLISH_TO_LIBRARY":
                return executePublishToLibrary(run);
            default:
                throw new IllegalArgumentException("未知步骤: " + stepName);
        }
    }

    private String executeValidateInput(WorkflowRun run) {
        log.info("验证输入");
        // TODO: 验证项目输入数据
        return "CAPTURE_MERCHANT_SNAPSHOT";
    }

    private String executeCaptureSnapshot(WorkflowRun run) {
        log.info("捕获商家快照");
        // TODO: 调用 Platform API 创建快照
        return "GENERATE_CREATIVE";
    }

    private String executeGenerateCreative(WorkflowRun run) {
        log.info("生成创意");
        // TODO: 调用 AI Gateway 生成创意
        return "HUMAN_APPROVE_CREATIVE";
    }

    private String executeGenerateScript(WorkflowRun run) {
        log.info("生成脚本");
        // TODO: 调用 AI Gateway 生成脚本
        return "HUMAN_APPROVE_SCRIPT";
    }

    private String executeGenerateStoryboard(WorkflowRun run) {
        log.info("生成分镜");
        // TODO: 调用 AI Gateway 生成分镜
        return "HUMAN_APPROVE_STORYBOARD";
    }

    private String executeGenerateReferenceImages(WorkflowRun run) {
        log.info("生成参考图");
        // TODO: 调用 AI Gateway 生成参考图
        return "SELECT_REFERENCE_IMAGES";
    }

    private String executeGenerateShots(WorkflowRun run) {
        log.info("生成镜头视频");
        // TODO: 调用 AI Gateway 生成视频
        return "CHECK_SHOTS";
    }

    private String executeCheckShots(WorkflowRun run) {
        log.info("检查镜头质量");
        // TODO: 调用质量检查服务
        return "COMPOSE_VIDEO";
    }

    private String executeComposeVideo(WorkflowRun run) {
        log.info("合成视频");
        // TODO: 调用 Python Worker 合成视频
        return "FINAL_QA";
    }

    private String executeFinalQA(WorkflowRun run) {
        log.info("最终质检");
        // TODO: 执行最终质检
        return "HUMAN_REVIEW";
    }

    private String executePublishToLibrary(WorkflowRun run) {
        log.info("发布到素材库");
        // TODO: 创建成品资产记录
        return null;  // 工作流完成
    }
}
