package com.wuyao.vimax.repository;

import com.wuyao.vimax.entity.GenerationTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 生成任务 Repository
 */
@Repository
public interface GenerationTaskRepository extends JpaRepository<GenerationTask, Long> {

    /**
     * 查询项目的所有任务
     */
    List<GenerationTask> findByVideoProjectIdOrderByStepNumberAsc(Long videoProjectId);

    /**
     * 查询项目的当前任务
     */
    Optional<GenerationTask> findByVideoProjectIdAndStatus(Long videoProjectId, String status);

    /**
     * 查询待人工审核的任务
     */
    @Query("SELECT t FROM GenerationTask t WHERE t.requiresHumanReview = true " +
           "AND t.status = 'WAITING_HUMAN_REVIEW' " +
           "ORDER BY t.createdAt ASC")
    List<GenerationTask> findPendingHumanReview();

    /**
     * 查询项目的特定步骤
     */
    Optional<GenerationTask> findByVideoProjectIdAndStepNumber(Long videoProjectId, Integer stepNumber);
}
