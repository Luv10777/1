package com.wuyao.growth.video;

import com.wuyao.growth.common.task.Task;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoQaHandlerTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void unexpectedQaFailuresRetryButExhaustionAlsoEndsTheWorkflow(int attempts) {
        var service = mock(VideoWorkflowService.class);
        var task = new Task();
        task.setPayload(Map.of("workflowId", 42L));
        task.setAttempts(attempts);
        task.setMaxAttempts(3);
        var original = com.wuyao.growth.common.web.BizException.of(
                com.wuyao.growth.common.web.ErrorCode.STORAGE_UNAVAILABLE, "MinIO unavailable");
        doThrow(original).when(service).completeQa(42L, task);
        assertThatThrownBy(() -> new VideoQaHandler(service).handle(task)).isSameAs(original);
        if (attempts == 3) verify(service).markFailed(42L, task, "VIDEO_QA_FAILED", "MinIO unavailable");
        else verify(service, never()).markFailed(eq(42L), any(Task.class), anyString(), anyString());
    }
}
