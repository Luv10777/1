package com.wuyao.vimax.repository;

import com.wuyao.vimax.entity.WorkflowStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * WorkflowStep Repository
 */
@Repository
public interface WorkflowStepRepository extends JpaRepository<WorkflowStep, Long> {

    /**
     * 查询工作流的所有步骤
     */
    List<WorkflowStep> findByWorkflowRunIdOrderBySequenceOrderAsc(Long workflowRunId);

    /**
     * 查询工作流的指定步骤
     */
    Optional<WorkflowStep> findByWorkflowRunIdAndStepCode(Long workflowRunId, String stepCode);

    /**
     * 查询待执行的步骤
     */
    List<WorkflowStep> findByWorkflowRunIdAndState(Long workflowRunId, String state);
}
