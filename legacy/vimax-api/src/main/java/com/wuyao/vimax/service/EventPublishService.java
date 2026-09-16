package com.wuyao.vimax.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.vimax.entity.MerchantFactSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * 事件发布服务
 *
 * 负责在业务操作后发布事件到 Outbox
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventPublishService {

    private final OutboxEventService outboxEventService;
    private final ObjectMapper objectMapper;

    /**
     * 发布商家快照创建事件
     */
    @Transactional
    public void publishSnapshotCreated(MerchantFactSnapshot snapshot) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("snapshotId", snapshot.getId());
            payload.put("merchantId", snapshot.getMerchantId());
            payload.put("snapshotCode", snapshot.getSnapshotCode());

            outboxEventService.createEvent(
                "SNAPSHOT_CREATED",
                "MerchantFactSnapshot",
                snapshot.getId(),
                objectMapper.writeValueAsString(payload)
            );

            log.info("快照创建事件已发布: snapshotId={}", snapshot.getId());

        } catch (Exception e) {
            log.error("发布快照创建事件失败", e);
        }
    }

    /**
     * 发布工作流启动事件
     */
    @Transactional
    public void publishWorkflowStarted(Long workflowRunId, Long projectId) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("workflowRunId", workflowRunId);
            payload.put("projectId", projectId);

            outboxEventService.createEvent(
                "WORKFLOW_STARTED",
                "WorkflowRun",
                workflowRunId,
                objectMapper.writeValueAsString(payload)
            );

            log.info("工作流启动事件已发布: workflowRunId={}", workflowRunId);

        } catch (Exception e) {
            log.error("发布工作流启动事件失败", e);
        }
    }

    /**
     * 发布工作流完成事件
     */
    @Transactional
    public void publishWorkflowCompleted(Long workflowRunId, String finalVideoUrl) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("workflowRunId", workflowRunId);
            payload.put("finalVideoUrl", finalVideoUrl);

            outboxEventService.createEvent(
                "WORKFLOW_COMPLETED",
                "WorkflowRun",
                workflowRunId,
                objectMapper.writeValueAsString(payload)
            );

            log.info("工作流完成事件已发布: workflowRunId={}", workflowRunId);

        } catch (Exception e) {
            log.error("发布工作流完成事件失败", e);
        }
    }

    /**
     * 发布质检完成事件
     */
    @Transactional
    public void publishQualityCheckCompleted(Long reportId, String result) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("reportId", reportId);
            payload.put("result", result);

            outboxEventService.createEvent(
                "QUALITY_CHECK_COMPLETED",
                "QualityReport",
                reportId,
                objectMapper.writeValueAsString(payload)
            );

            log.info("质检完成事件已发布: reportId={}", reportId);

        } catch (Exception e) {
            log.error("发布质检完成事件失败", e);
        }
    }
}
