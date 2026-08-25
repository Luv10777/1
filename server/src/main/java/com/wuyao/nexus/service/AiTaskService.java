package com.wuyao.nexus.service;

import com.wuyao.nexus.dto.TaskCreateRequest;
import com.wuyao.nexus.dto.TaskResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;

public interface AiTaskService {
    /**
     * 创建AI任务
     */
    TaskResponse createTask(Long tenantId, Long userId, TaskCreateRequest request);

    /**
     * 获取任务列表
     */
    Page<TaskResponse> listTasks(Long tenantId, Pageable pageable);

    /**
     * 获取任务详情
     */
    TaskResponse getTask(Long id, Long tenantId);

    /**
     * 取消任务
     */
    void cancelTask(Long id, Long tenantId);

    /**
     * 重试任务
     */
    TaskResponse retryTask(Long id, Long tenantId);

    /**
     * 成本预估
     */
    BigDecimal estimateCost(String modelAlias, Object inputParams);

    /**
     * 处理任务队列
     */
    void processPendingTasks();

    /**
     * 处理回调队列
     */
    void processCallbacks();
}
