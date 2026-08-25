package com.wuyao.nexus.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.nexus.dto.TaskCreateRequest;
import com.wuyao.nexus.dto.TaskResponse;
import com.wuyao.nexus.entity.AiTask;
import com.wuyao.nexus.entity.ModelAlias;
import com.wuyao.nexus.exception.BusinessException;
import com.wuyao.nexus.repository.AiTaskRepository;
import com.wuyao.nexus.repository.ModelAliasRepository;
import com.wuyao.nexus.service.AiTaskService;
import com.wuyao.nexus.service.QuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiTaskServiceImpl implements AiTaskService {

    private final AiTaskRepository taskRepository;
    private final ModelAliasRepository modelAliasRepository;
    private final QuotaService quotaService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public TaskResponse createTask(Long tenantId, Long userId, TaskCreateRequest request) {
        // 查找模型
        ModelAlias model = modelAliasRepository.findByAliasAndStatus(
                request.getModelAlias(), ModelAlias.ModelStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException("模型不存在或已禁用"));

        // 成本预估
        BigDecimal estimatedCost = estimateCost(request.getModelAlias(), request.getInputParams());

        // 检查配额
        quotaService.checkAndReserve(tenantId, estimatedCost);

        // 创建任务
        AiTask task = new AiTask();
        task.setTenantId(tenantId);
        task.setCode("T" + System.currentTimeMillis());
        task.setType(AiTask.TaskType.valueOf(request.getType()));
        task.setModelAlias(request.getModelAlias());
        task.setProviderId(model.getProviderId());

        try {
            task.setInputParams(objectMapper.writeValueAsString(request.getInputParams()));
        } catch (Exception e) {
            throw new BusinessException("输入参数序列化失败");
        }

        task.setEstimatedCost(estimatedCost);
        task.setWebhookUrl(request.getWebhookUrl());
        task.setPriority(request.getPriority() != null ? request.getPriority() : 50);
        task.setCreatedBy(userId);
        task.setStatus(AiTask.TaskStatus.PENDING);

        task = taskRepository.save(task);

        log.info("AI任务已创建: taskId={}, type={}, model={}", task.getId(), task.getType(), task.getModelAlias());

        return toResponse(task);
    }

    @Override
    public Page<TaskResponse> listTasks(Long tenantId, Pageable pageable) {
        return taskRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable)
                .map(this::toResponse);
    }

    @Override
    public TaskResponse getTask(Long id, Long tenantId) {
        AiTask task = taskRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new BusinessException("任务不存在"));
        return toResponse(task);
    }

    @Override
    @Transactional
    public void cancelTask(Long id, Long tenantId) {
        AiTask task = taskRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new BusinessException("任务不存在"));

        if (task.getStatus() != AiTask.TaskStatus.PENDING &&
            task.getStatus() != AiTask.TaskStatus.QUEUED) {
            throw new BusinessException("任务状态不允许取消");
        }

        task.setStatus(AiTask.TaskStatus.CANCELLED);
        task.setCompletedAt(LocalDateTime.now());
        taskRepository.save(task);

        // 释放预留配额
        if (task.getEstimatedCost() != null) {
            quotaService.release(tenantId, task.getEstimatedCost());
        }

        log.info("任务已取消: taskId={}", id);
    }

    @Override
    @Transactional
    public TaskResponse retryTask(Long id, Long tenantId) {
        AiTask task = taskRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new BusinessException("任务不存在"));

        if (task.getStatus() != AiTask.TaskStatus.FAILED) {
            throw new BusinessException("只有失败的任务可以重试");
        }

        if (task.getRetryCount() >= task.getMaxRetries()) {
            throw new BusinessException("已达到最大重试次数");
        }

        task.setStatus(AiTask.TaskStatus.PENDING);
        task.setRetryCount(task.getRetryCount() + 1);
        task.setErrorCode(null);
        task.setErrorMessage(null);
        task.setStartedAt(null);
        task.setCompletedAt(null);

        task = taskRepository.save(task);

        log.info("任务已重试: taskId={}, retryCount={}", id, task.getRetryCount());

        return toResponse(task);
    }

    @Override
    public BigDecimal estimateCost(String modelAlias, Object inputParams) {
        ModelAlias model = modelAliasRepository.findByAliasAndStatus(
                modelAlias, ModelAlias.ModelStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException("模型不存在"));

        // 简单成本估算逻辑
        // 实际应该根据inputParams的具体内容（文本长度、图片分辨率等）计算
        if (model.getCostPer1kTokens() != null) {
            return model.getCostPer1kTokens().multiply(BigDecimal.TEN); // 假设平均10k tokens
        } else if (model.getCostPerImage() != null) {
            return model.getCostPerImage();
        } else if (model.getCostPerVideoSecond() != null) {
            return model.getCostPerVideoSecond().multiply(BigDecimal.TEN); // 假设平均10秒
        }

        return BigDecimal.ZERO;
    }

    @Override
    @Transactional
    public void processPendingTasks() {
        List<AiTask> tasks = taskRepository.findPendingTasks(
                List.of(AiTask.TaskStatus.PENDING, AiTask.TaskStatus.QUEUED),
                PageRequest.of(0, 10)
        );

        for (AiTask task : tasks) {
            try {
                processTask(task);
            } catch (Exception e) {
                log.error("处理任务失败: taskId={}", task.getId(), e);
                handleTaskError(task, "PROCESSING_ERROR", e.getMessage());
            }
        }
    }

    @Override
    public void processCallbacks() {
        List<AiTask> tasks = taskRepository.findTasksNeedingCallback(PageRequest.of(0, 10));

        for (AiTask task : tasks) {
            try {
                sendCallback(task);
            } catch (Exception e) {
                log.error("发送回调失败: taskId={}", task.getId(), e);
                task.setCallbackAttempts(task.getCallbackAttempts() + 1);
                if (task.getCallbackAttempts() >= 3) {
                    task.setCallbackStatus(AiTask.CallbackStatus.FAILED);
                }
                taskRepository.save(task);
            }
        }
    }

    private void processTask(AiTask task) {
        // TODO: 实际调用AI Provider的API
        // 这里是占位符，实际需要根据providerId调用对应的Provider

        task.setStatus(AiTask.TaskStatus.PROCESSING);
        task.setStartedAt(LocalDateTime.now());
        task.setProgress(0);
        taskRepository.save(task);

        log.info("开始处理任务: taskId={}, type={}", task.getId(), task.getType());

        // 模拟处理完成
        task.setStatus(AiTask.TaskStatus.COMPLETED);
        task.setProgress(100);
        task.setCompletedAt(LocalDateTime.now());
        task.setResult("{\"status\": \"mock_completed\"}");

        if (task.getWebhookUrl() != null) {
            task.setCallbackStatus(AiTask.CallbackStatus.PENDING);
        }

        taskRepository.save(task);

        // 扣除实际成本
        if (task.getEstimatedCost() != null) {
            quotaService.charge(task.getTenantId(), task.getEstimatedCost());
        }
    }

    private void handleTaskError(AiTask task, String errorCode, String errorMessage) {
        task.setStatus(AiTask.TaskStatus.FAILED);
        task.setErrorCode(errorCode);
        task.setErrorMessage(errorMessage);
        task.setCompletedAt(LocalDateTime.now());
        taskRepository.save(task);

        // 释放预留配额
        if (task.getEstimatedCost() != null) {
            quotaService.release(task.getTenantId(), task.getEstimatedCost());
        }
    }

    private void sendCallback(AiTask task) {
        // TODO: 实际发送HTTP回调
        log.info("发送回调: taskId={}, url={}", task.getId(), task.getWebhookUrl());

        task.setCallbackStatus(AiTask.CallbackStatus.SUCCESS);
        task.setCallbackAttempts(task.getCallbackAttempts() + 1);
        taskRepository.save(task);
    }

    private TaskResponse toResponse(AiTask task) {
        TaskResponse response = new TaskResponse();
        BeanUtils.copyProperties(task, response);
        response.setType(task.getType().name());
        response.setStatus(task.getStatus().name());
        return response;
    }
}
