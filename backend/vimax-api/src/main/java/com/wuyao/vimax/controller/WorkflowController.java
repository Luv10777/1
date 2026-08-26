package com.wuyao.vimax.controller;

import com.wuyao.vimax.dto.ApiResponse;
import com.wuyao.vimax.dto.CreateVideoProjectRequest;
import com.wuyao.vimax.dto.HumanReviewRequest;
import com.wuyao.vimax.entity.VideoProject;
import com.wuyao.vimax.entity.WorkflowRun;
import com.wuyao.vimax.service.workflow.WorkflowEngineService;
import com.wuyao.vimax.service.VideoProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 工作流 Controller
 */
@RestController
@RequestMapping("/workflow")
@RequiredArgsConstructor
@Slf4j
public class WorkflowController {

    private final WorkflowEngineService workflowEngineService;
    private final VideoProjectService videoProjectService;

    /**
     * 创建视频项目
     */
    @PostMapping("/projects")
    public ApiResponse<VideoProject> createProject(@RequestBody CreateVideoProjectRequest request,
                                                   @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId) {
        log.info("创建视频项目: merchantId={}", request.getMerchantId());

        VideoProject project = videoProjectService.createProject(
                request.getMerchantId(),
                request.getProjectName(),
                request.getUserInput(),
                userId
        );

        return ApiResponse.success("项目创建成功", project);
    }

    /**
     * 启动工作流
     */
    @PostMapping("/projects/{projectId}/start")
    public ApiResponse<WorkflowRun> startWorkflow(@PathVariable Long projectId) {
        log.info("启动工作流: projectId={}", projectId);

        WorkflowRun run = workflowEngineService.startWorkflow(projectId);
        return ApiResponse.success("工作流已启动", run);
    }

    /**
     * 人工审核
     */
    @PostMapping("/runs/{runId}/human-review")
    public ApiResponse<Void> humanReview(@PathVariable Long runId,
                                        @RequestBody HumanReviewRequest request,
                                        @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId) {
        log.info("人工审核: runId={}, approved={}", runId, request.getApproved());

        // TODO: 实现人工审核逻辑
        return ApiResponse.success("审核已提交", null);
    }

    /**
     * 查询工作流状态
     */
    @GetMapping("/runs/{runId}")
    public ApiResponse<WorkflowRun> getWorkflowStatus(@PathVariable String runId) {
        log.info("查询工作流状态: runId={}", runId);

        // TODO: 实现按 runId 字符串查询（需要修改为按 Long 查询）
        WorkflowRun run = new WorkflowRun();
        return ApiResponse.success(run);
    }

    /**
     * 查询项目列表
     */
    @GetMapping("/projects")
    public ApiResponse<List<VideoProject>> listProjects(@RequestParam Long merchantId) {
        log.info("查询项目列表: merchantId={}", merchantId);

        List<VideoProject> projects = videoProjectService.listProjects(merchantId);
        return ApiResponse.success(projects);
    }

    /**
     * 查询待审核列表
     */
    @GetMapping("/runs/pending-review")
    public ApiResponse<List<WorkflowRun>> listPendingReview() {
        log.info("查询待审核列表");

        // TODO: 实现待审核任务查询
        List<WorkflowRun> runs = List.of();
        return ApiResponse.success(runs);
    }
}
