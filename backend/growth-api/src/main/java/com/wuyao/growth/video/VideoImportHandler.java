package com.wuyao.growth.video;

import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class VideoImportHandler implements TaskHandler {
    public static final String TYPE = "VIDEO_IMPORT";
    private final VideoWorkflowService service;
    private final VideoWorkflowRepository workflows;

    @Override public String type() { return TYPE; }

    @Override
    public Map<String, Object> handle(Task task) {
        Long workflowId = ((Number) task.getPayload().get("workflowId")).longValue();
        if (!service.beginImport(workflowId, task)) return Map.of("status", "STALE");
        try {
            VideoWorkflow workflow = workflows.findById(workflowId).orElseThrow();
            var imported = service.importUrl(workflow);
            service.saveImportedVideo(workflowId, task, imported.storageKey(), imported.sizeBytes(), imported.contentType());
            return Map.of("status", "IMPORTED", "storageKey", imported.storageKey());
        } catch (RuntimeException e) {
            if (task.getAttempts() >= task.getMaxAttempts()) service.markFailed(workflowId, "VIDEO_IMPORT_FAILED", e.getMessage());
            throw e;
        }
    }
}
