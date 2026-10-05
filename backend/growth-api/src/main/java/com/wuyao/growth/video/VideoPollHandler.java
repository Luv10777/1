package com.wuyao.growth.video;

import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskHandler;
import com.wuyao.growth.common.task.NonRetryableTaskException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class VideoPollHandler implements TaskHandler {
    public static final String TYPE = "VIDEO_POLL";
    private final VideoWorkflowService service;
    private final VideoProviderGateway provider;
    private final VideoWorkflowRepository workflows;

    @Override public String type() { return TYPE; }

    @Override
    public Map<String, Object> handle(Task task) {
        Long workflowId = ((Number) task.getPayload().get("workflowId")).longValue();
        if (!service.beginPoll(workflowId, task)) return Map.of("status", "STALE");
        try {
            VideoWorkflow workflow = workflows.findById(workflowId).orElseThrow();
            var result = provider.poll(workflow.getProviderJobId(), "video-" + workflowId + "-poll-" + workflow.getPollRound());
            service.savePoll(workflowId, task, result);
            return Map.of("status", result.status(), "hasUrl", result.resultUrl() != null);
        } catch (RuntimeException e) {
            if (e instanceof VideoProviderGateway.ProviderHttpException providerError && !providerError.retryable()) {
                service.markFailed(workflowId, "VIDEO_PROVIDER_FAILED", providerError.getMessage());
                throw new NonRetryableTaskException("VIDEO_PROVIDER_ERROR", providerError.getMessage(), providerError);
            }
            if (task.getAttempts() >= task.getMaxAttempts()) service.markFailed(workflowId, "VIDEO_POLL_FAILED", e.getMessage());
            throw e;
        }
    }
}
