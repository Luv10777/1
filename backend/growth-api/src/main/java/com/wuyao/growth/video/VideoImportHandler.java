package com.wuyao.growth.video;

import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.NonRetryableTaskException;
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
        String key = null;
        try {
            if (!service.beginImport(workflowId, task)) return Map.of("status", "STALE");
            var reserved = service.reserveImportKey(workflowId, task);
            if (reserved.isEmpty()) return Map.of("status", "STALE");
            key = reserved.get();
            VideoWorkflow workflow = workflows.findById(workflowId).orElseThrow();
            var imported = service.importUrl(workflow, key);
            service.saveImportedVideo(workflowId, task, imported.storageKey(), imported.sizeBytes(), imported.contentType());
            return Map.of("status", "IMPORTED", "storageKey", imported.storageKey());
        } catch (RuntimeException e) {
            if (key != null) service.cleanupImportObject(workflowId, key);
            if (e instanceof VideoImportException importError && !importError.retryable()) {
                // Restoring the original provider job can refresh an unavailable URL without a new paid submission.
                String code = importError.reason() == VideoImportException.Reason.RESULT_URL_UNAVAILABLE
                        ? "VIDEO_IMPORT_FAILED" : "VIDEO_IMPORT_REJECTED";
                service.markFailed(workflowId, task, code, importError.getMessage());
                throw new NonRetryableTaskException(importError.errorCode(), importError.getMessage(), importError);
            }
            if (task.getAttempts() >= task.getMaxAttempts()) service.markFailed(workflowId, task, "VIDEO_IMPORT_FAILED", e.getMessage());
            throw e;
        }
    }
}
