package com.wuyao.vimax.controller;

import com.wuyao.vimax.dto.ApiResponse;
import com.wuyao.vimax.entity.QualityReport;
import com.wuyao.vimax.service.QualityCheckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 质量检查 Controller
 */
@RestController
@RequestMapping("/quality")
@RequiredArgsConstructor
@Slf4j
public class QualityController {

    private final QualityCheckService qualityCheckService;

    /**
     * 执行技术质检
     */
    @PostMapping("/technical-check")
    public ApiResponse<QualityReport> performTechnicalCheck(
            @RequestParam Long workflowRunId,
            @RequestParam String videoUrl) {
        log.info("执行技术质检: workflowRunId={}", workflowRunId);

        QualityReport report = qualityCheckService.performTechnicalCheck(workflowRunId, videoUrl);
        return ApiResponse.success("技术质检完成", report);
    }

    /**
     * 执行语义质检
     */
    @PostMapping("/semantic-check")
    public ApiResponse<QualityReport> performSemanticCheck(
            @RequestParam Long workflowRunId,
            @RequestBody String content) {
        log.info("执行语义质检: workflowRunId={}", workflowRunId);

        QualityReport report = qualityCheckService.performSemanticCheck(workflowRunId, content);
        return ApiResponse.success("语义质检完成", report);
    }

    /**
     * 执行事实质检
     */
    @PostMapping("/fact-check")
    public ApiResponse<QualityReport> performFactCheck(
            @RequestParam Long workflowRunId,
            @RequestParam Long snapshotId,
            @RequestBody String content) {
        log.info("执行事实质检: workflowRunId={}", workflowRunId);

        QualityReport report = qualityCheckService.performFactCheck(workflowRunId, snapshotId, content);
        return ApiResponse.success("事实质检完成", report);
    }

    /**
     * 查询质量报告
     */
    @GetMapping("/reports")
    public ApiResponse<List<QualityReport>> getReports(@RequestParam Long workflowRunId) {
        log.info("查询质量报告: workflowRunId={}", workflowRunId);

        List<QualityReport> reports = qualityCheckService.getReports(workflowRunId);
        return ApiResponse.success(reports);
    }
}
