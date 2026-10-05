package com.wuyao.growth.video;

import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskHandler;
import com.wuyao.growth.common.task.NonRetryableTaskException;
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
            var candidate = workflow.getProviderSubmitRequest();
            if (candidate == null || candidate.isEmpty()) candidate = provider.buildSubmitRequest(workflow.getRequest());
            var request = service.prepareSubmissionRequest(workflowId, task, candidate);
            if (request.isEmpty()) return Map.of("status", "STALE");
            VideoProviderGateway.SubmitResult result = provider.submit(request.get(), "video-" + workflowId + "-submit");
            service.saveSubmission(workflowId, task, result);
            return Map.of("status", "SUBMITTED", "providerJobId", result.providerJobId());
        } catch (RuntimeException e) {
            if (e instanceof VideoProviderGateway.ProviderHttpException providerError && !providerError.retryable()) {
                service.markFailed(workflowId, "VIDEO_PROVIDER_FAILED", providerError.getMessage());
                throw new NonRetryableTaskException("VIDEO_PROVIDER_ERROR", providerError.getMessage(), providerError);
            }
            if (task.getAttempts() >= task.getMaxAttempts()) service.markFailed(workflowId, "VIDEO_SUBMIT_FAILED", e.getMessage());
            throw e;
        }
    }
}
