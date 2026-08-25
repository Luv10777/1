package com.wuyao.nexus.controller;

import com.wuyao.nexus.dto.ApiResponse;
import com.wuyao.nexus.dto.TaskCreateRequest;
import com.wuyao.nexus.dto.TaskResponse;
import com.wuyao.nexus.service.AiTaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final AiTaskService taskService;

    @PostMapping
    public ApiResponse<TaskResponse> createTask(
            @RequestAttribute("tenantId") Long tenantId,
            @RequestAttribute("userId") Long userId,
            @Valid @RequestBody TaskCreateRequest request) {
        TaskResponse task = taskService.createTask(tenantId, userId, request);
        return ApiResponse.success(task, "任务创建成功");
    }

    @GetMapping
    public ApiResponse<Page<TaskResponse>> listTasks(
            @RequestAttribute("tenantId") Long tenantId,
            Pageable pageable) {
        Page<TaskResponse> tasks = taskService.listTasks(tenantId, pageable);
        return ApiResponse.success(tasks);
    }

    @GetMapping("/{id}")
    public ApiResponse<TaskResponse> getTask(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId) {
        TaskResponse task = taskService.getTask(id, tenantId);
        return ApiResponse.success(task);
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Void> cancelTask(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId) {
        taskService.cancelTask(id, tenantId);
        return ApiResponse.success(null, "任务已取消");
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<TaskResponse> retryTask(
            @PathVariable Long id,
            @RequestAttribute("tenantId") Long tenantId) {
        TaskResponse task = taskService.retryTask(id, tenantId);
        return ApiResponse.success(task, "任务已重试");
    }

    @PostMapping("/estimate")
    public ApiResponse<Map<String, Object>> estimateCost(
            @RequestBody Map<String, Object> request) {
        String modelAlias = (String) request.get("modelAlias");
        Object inputParams = request.get("inputParams");
        BigDecimal cost = taskService.estimateCost(modelAlias, inputParams);
        return ApiResponse.success(Map.of("estimatedCost", cost));
    }
}
