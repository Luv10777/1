package com.wuyao.vimax.service;

import com.wuyao.vimax.entity.GenerationTask;
import com.wuyao.vimax.entity.WorkflowRun;
import com.wuyao.vimax.repository.GenerationTaskRepository;
import com.wuyao.vimax.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 成本计算服务
 *
 * Phase 2.3: 计算和追踪任务成本
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CostCalculationService {

    private final GenerationTaskRepository taskRepository;
    private final WorkflowRunRepository runRepository;

    // 价格表（美元）
    private static final BigDecimal PRICE_TEXT_PER_1K_TOKENS = new BigDecimal("0.002");
    private static final BigDecimal PRICE_IMAGE_1024_STANDARD = new BigDecimal("0.04");
    private static final BigDecimal PRICE_IMAGE_1024_HD = new BigDecimal("0.08");
    private static final BigDecimal PRICE_IMAGE_1792_STANDARD = new BigDecimal("0.08");
    private static final BigDecimal PRICE_IMAGE_1792_HD = new BigDecimal("0.16");
    private static final BigDecimal PRICE_VIDEO_5S = new BigDecimal("0.20");

    /**
     * 估算文本生成成本
     */
    public BigDecimal estimateTextCost(int estimatedTokens) {
        return PRICE_TEXT_PER_1K_TOKENS
            .multiply(BigDecimal.valueOf(estimatedTokens))
            .divide(BigDecimal.valueOf(1000), 4, BigDecimal.ROUND_HALF_UP);
    }

    /**
     * 估算图片生成成本
     */
    public BigDecimal estimateImageCost(String size, String quality) {
        if ("hd".equalsIgnoreCase(quality)) {
            return size != null && size.contains("1792") ? PRICE_IMAGE_1792_HD : PRICE_IMAGE_1024_HD;
        } else {
            return size != null && size.contains("1792") ? PRICE_IMAGE_1792_STANDARD : PRICE_IMAGE_1024_STANDARD;
        }
    }

    /**
     * 估算视频生成成本
     */
    public BigDecimal estimateVideoCost(int durationSeconds) {
        // 按5秒为基准，线性计算
        return PRICE_VIDEO_5S
            .multiply(BigDecimal.valueOf(durationSeconds))
            .divide(BigDecimal.valueOf(5), 4, BigDecimal.ROUND_HALF_UP);
    }

    /**
     * 计算工作流总成本
     */
    @Transactional
    public void calculateWorkflowCost(Long workflowRunId) {
        log.info("计算工作流成本: runId={}", workflowRunId);

        WorkflowRun run = runRepository.findById(workflowRunId)
            .orElseThrow(() -> new IllegalArgumentException("工作流运行不存在"));

        List<GenerationTask> tasks = taskRepository.findByWorkflowRunIdOrderByCreatedAtAsc(workflowRunId);

        BigDecimal totalEstimatedCost = BigDecimal.ZERO;
        BigDecimal totalActualCost = BigDecimal.ZERO;

        for (GenerationTask task : tasks) {
            if (task.getEstimatedCost() != null) {
                totalEstimatedCost = totalEstimatedCost.add(task.getEstimatedCost());
            }
            if (task.getActualCost() != null) {
                totalActualCost = totalActualCost.add(task.getActualCost());
            }
        }

        run.setEstimatedCostCredits(totalEstimatedCost);
        run.setActualCostCredits(totalActualCost);
        runRepository.save(run);

        log.info("工作流成本已计算: runId={}, estimated=${}, actual=${}",
            workflowRunId, totalEstimatedCost, totalActualCost);
    }

    /**
     * 预占成本（用于额度检查）
     */
    @Transactional
    public void reserveCost(Long workflowRunId, BigDecimal amount) {
        log.info("预占成本: runId={}, amount=${}", workflowRunId, amount);

        WorkflowRun run = runRepository.findById(workflowRunId)
            .orElseThrow(() -> new IllegalArgumentException("工作流运行不存在"));

        BigDecimal currentReserved = run.getReservedCredits() != null ? run.getReservedCredits() : BigDecimal.ZERO;
        run.setReservedCredits(currentReserved.add(amount));
        runRepository.save(run);
    }

    /**
     * 释放预占成本（任务失败或取消）
     */
    @Transactional
    public void releaseCost(Long workflowRunId, BigDecimal amount) {
        log.info("释放预占成本: runId={}, amount=${}", workflowRunId, amount);

        WorkflowRun run = runRepository.findById(workflowRunId)
            .orElseThrow(() -> new IllegalArgumentException("工作流运行不存在"));

        BigDecimal currentReserved = run.getReservedCredits() != null ? run.getReservedCredits() : BigDecimal.ZERO;
        run.setReservedCredits(currentReserved.subtract(amount).max(BigDecimal.ZERO));
        runRepository.save(run);
    }

    /**
     * 记录实际成本
     */
    @Transactional
    public void recordActualCost(Long taskId, BigDecimal actualCost) {
        log.info("记录实际成本: taskId={}, cost=${}", taskId, actualCost);

        GenerationTask task = taskRepository.findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("任务不存在"));

        task.setActualCost(actualCost);
        taskRepository.save(task);

        // 重新计算工作流总成本
        if (task.getWorkflowRunId() != null) {
            calculateWorkflowCost(task.getWorkflowRunId());
        }
    }
}
