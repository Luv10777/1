package com.wuyao.vimax.repository;

import com.wuyao.vimax.entity.WorkflowRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 工作流运行 Repository
 */
@Repository
public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, Long> {

    /**
     * 根据 runId 查找
     */
    Optional<WorkflowRun> findByRunId(String runId);

    /**
     * 查询项目的工作流运行记录
     */
    List<WorkflowRun> findByVideoProjectIdOrderByCreatedAtDesc(Long videoProjectId);

    /**
     * 查询待人工审核的工作流
     */
    @Query("SELECT r FROM WorkflowRun r WHERE r.pausedForHumanReview = true " +
           "AND r.status = 'RUNNING' " +
           "ORDER BY r.createdAt ASC")
    List<WorkflowRun> findPendingHumanReview();

    /**
     * 查询运行中的工作流
     */
    List<WorkflowRun> findByStatusOrderByCreatedAtAsc(String status);
}
