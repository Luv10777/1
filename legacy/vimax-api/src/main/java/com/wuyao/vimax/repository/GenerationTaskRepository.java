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
    List<GenerationTask> findByWorkflowRunIdOrderByCreatedAtAsc(Long workflowRunId);

    /**
     * 根据输入哈希查找任务（幂等性）
     */
    Optional<GenerationTask> findByInputHash(String inputHash);

    /**
     * 根据幂等性Key查找任务
     */
    Optional<GenerationTask> findByIdempotencyKey(String idempotencyKey);
}
