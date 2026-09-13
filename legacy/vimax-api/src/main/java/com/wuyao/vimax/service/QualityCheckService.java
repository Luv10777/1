package com.wuyao.vimax.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.vimax.entity.QualityReport;
import com.wuyao.vimax.repository.QualityReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 质量检查服务
 *
 * 负责视频和内容的质量检查
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QualityCheckService {

    private final QualityReportRepository reportRepository;
    private final ObjectMapper objectMapper;

    /**
     * 技术质检
     */
    @Transactional
    public QualityReport performTechnicalCheck(Long workflowRunId, String videoUrl) {
        log.info("执行技术质检: workflowRunId={}, videoUrl={}", workflowRunId, videoUrl);

        try {
            Map<String, Object> reportData = new HashMap<>();
            int issuesFound = 0;

            // TODO: 调用 Python Worker 进行技术质检
            // 1. 检查分辨率
            // 2. 检查时长
            // 3. 检查帧率
            // 4. 检查音频
            // 5. 检查文件大小

            reportData.put("resolution", "1920x1080");
            reportData.put("duration", "30s");
            reportData.put("has_audio", true);
            reportData.put("file_size", "15MB");

            QualityReport report = new QualityReport();
            report.setWorkflowRunId(workflowRunId);
            report.setCheckType("TECHNICAL");
            report.setCheckScope("VIDEO");
            report.setOverallResult(issuesFound == 0 ? "PASS" : "FAIL");
            report.setIssuesFound(issuesFound);
            report.setReportData(objectMapper.writeValueAsString(reportData));

            QualityReport saved = reportRepository.save(report);
            log.info("技术质检完成: reportId={}, result={}", saved.getId(), saved.getOverallResult());

            return saved;

        } catch (Exception e) {
            log.error("技术质检失败", e);
            throw new RuntimeException("技术质检失败: " + e.getMessage());
        }
    }

    /**
     * 语义质检
     */
    @Transactional
    public QualityReport performSemanticCheck(Long workflowRunId, String content) {
        log.info("执行语义质检: workflowRunId={}", workflowRunId);

        try {
            Map<String, Object> reportData = new HashMap<>();
            int issuesFound = 0;

            // TODO: 使用 AI 检查内容质量
            // 1. 检查语言流畅度
            // 2. 检查逻辑连贯性
            // 3. 检查敏感词
            // 4. 检查商标合规

            reportData.put("fluency_score", 0.95);
            reportData.put("coherence_score", 0.90);
            reportData.put("has_sensitive_words", false);

            QualityReport report = new QualityReport();
            report.setWorkflowRunId(workflowRunId);
            report.setCheckType("SEMANTIC");
            report.setCheckScope("CONTENT");
            report.setOverallResult(issuesFound == 0 ? "PASS" : "FAIL");
            report.setIssuesFound(issuesFound);
            report.setReportData(objectMapper.writeValueAsString(reportData));

            QualityReport saved = reportRepository.save(report);
            log.info("语义质检完成: reportId={}, result={}", saved.getId(), saved.getOverallResult());

            return saved;

        } catch (Exception e) {
            log.error("语义质检失败", e);
            throw new RuntimeException("语义质检失败: " + e.getMessage());
        }
    }

    /**
     * 事实质检
     */
    @Transactional
    public QualityReport performFactCheck(Long workflowRunId, Long snapshotId, String content) {
        log.info("执行事实质检: workflowRunId={}, snapshotId={}", workflowRunId, snapshotId);

        try {
            Map<String, Object> reportData = new HashMap<>();
            int issuesFound = 0;

            // TODO: 对比商家快照检查事实准确性
            // 1. 检查商家名称
            // 2. 检查产品信息
            // 3. 检查价格信息
            // 4. 检查营业时间

            reportData.put("fact_accuracy", 1.0);
            reportData.put("mismatches", 0);

            QualityReport report = new QualityReport();
            report.setWorkflowRunId(workflowRunId);
            report.setCheckType("FACT");
            report.setCheckScope("CONTENT");
            report.setOverallResult(issuesFound == 0 ? "PASS" : "FAIL");
            report.setIssuesFound(issuesFound);
            report.setReportData(objectMapper.writeValueAsString(reportData));

            QualityReport saved = reportRepository.save(report);
            log.info("事实质检完成: reportId={}, result={}", saved.getId(), saved.getOverallResult());

            return saved;

        } catch (Exception e) {
            log.error("事实质检失败", e);
            throw new RuntimeException("事实质检失败: " + e.getMessage());
        }
    }

    /**
     * 查询质量报告
     */
    public List<QualityReport> getReports(Long workflowRunId) {
        return reportRepository.findByWorkflowRunIdOrderByCreatedAtDesc(workflowRunId);
    }
}
