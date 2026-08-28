package com.wuyao.vimax.repository;

import com.wuyao.vimax.entity.QualityReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 质量报告 Repository
 */
@Repository
public interface QualityReportRepository extends JpaRepository<QualityReport, Long> {

    /**
     * 查询工作流的质量报告
     */
    List<QualityReport> findByWorkflowRunIdOrderByCreatedAtDesc(Long workflowRunId);

    /**
     * 按检查类型查询
     */
    List<QualityReport> findByCheckTypeAndOverallResult(String checkType, String overallResult);
}
