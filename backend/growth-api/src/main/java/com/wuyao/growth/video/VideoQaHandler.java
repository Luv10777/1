package com.wuyao.growth.video;

import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class VideoQaHandler implements TaskHandler {
    public static final String TYPE = "VIDEO_QA";
    private final VideoWorkflowService service;
    private final VideoProviderGateway provider;
    private final VideoWorkflowRepository workflows;

    @Override public String type() { return TYPE; }

    @Override
    public Map<String, Object> handle(Task task) {
        Long workflowId = ((Number) task.getPayload().get("workflowId")).longValue();
        try {
            service.completeQa(workflowId, task, billedQuota(workflowId));
            return Map.of("status", "PASSED", "workflowId", workflowId);
        } catch (RuntimeException e) {
            if (task.getAttempts() >= task.getMaxAttempts()) service.markFailed(workflowId, task, "VIDEO_QA_FAILED", e.getMessage());
            throw e;
        }
    }

    /** Ask the relay outside the settlement transaction, and only the relay that accepted the quote. */
    private Long billedQuota(Long workflowId) {
        VideoWorkflow workflow = workflows.findById(workflowId).orElse(null);
        if (workflow == null || workflow.getBillingReservationId() == null || workflow.getBillingQuote() == null
                || !Objects.equals(workflow.getBillingQuote().providerBaseUrl(), provider.baseUrl())) return null;
        return provider.billedQuota(workflow.getProviderJobId(), workflow.getProviderSubmitStartedAt());
    }
}
