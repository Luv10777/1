package com.wuyao.growth.video;

import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class VideoSubmitHandler implements TaskHandler {
    public static final String TYPE = "VIDEO_SUBMIT";
    private final VideoWorkflowService service;
    private final VideoProviderGateway provider;
    private final VideoWorkflowRepository workflows;

    @Override public String type() { return TYPE; }

    @Override
    public Map<String, Object> handle(Task task) {
        Long workflowId = ((Number) task.getPayload().get("workflowId")).longValue();
        if (!service.beginSubmit(workflowId, task)) return Map.of("status", "STALE");
        try {
            VideoWorkflow workflow = workflows.findById(workflowId).orElseThrow();
            VideoProviderGateway.SubmitResult result = provider.submit(workflow.getRequest(), "video-" + workflowId + "-submit");
            service.saveSubmission(workflowId, task, result);
            return Map.of("status", "SUBMITTED", "providerJobId", result.providerJobId());
        } catch (RuntimeException e) {
            if (task.getAttempts() >= task.getMaxAttempts()) service.markFailed(workflowId, "VIDEO_SUBMIT_FAILED", e.getMessage());
            throw e;
        }
    }
}
