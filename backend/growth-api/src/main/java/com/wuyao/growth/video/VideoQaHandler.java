package com.wuyao.growth.video;

import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class VideoQaHandler implements TaskHandler {
    public static final String TYPE = "VIDEO_QA";
    private final VideoWorkflowService service;

    @Override public String type() { return TYPE; }

    @Override
    public Map<String, Object> handle(Task task) {
        Long workflowId = ((Number) task.getPayload().get("workflowId")).longValue();
        try {
            service.completeQa(workflowId, task);
            return Map.of("status", "PASSED", "workflowId", workflowId);
        } catch (RuntimeException e) {
            if (task.getAttempts() >= task.getMaxAttempts()) service.markFailed(workflowId, task, "VIDEO_QA_FAILED", e.getMessage());
            throw e;
        }
    }
}
