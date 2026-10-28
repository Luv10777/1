package com.wuyao.growth.copywriting;

import com.wuyao.growth.common.task.TaskTerminalFailure;
import com.wuyao.growth.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** 改写和口吻总结不涉及烟火，任务彻底失败时在这里把「进行中」的标记收回。生成任务的退款由计费模块统一处理。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CopyTaskFailureListener {
    private final CopyService copies;
    private final CopyVoiceService voices;

    @TransactionalEventListener(fallbackExecution = true)
    public void failed(TaskTerminalFailure event) {
        try {
            if (CopyRewriteHandler.TYPE.equals(event.type())) TenantContext.runAs(event.tenantId(), () -> { copies.rewriteTaskFailed(event); return null; });
            if (CopyVoiceProfileHandler.TYPE.equals(event.type())) TenantContext.runAs(event.tenantId(), () -> { voices.taskFailed(event); return null; });
        } catch (RuntimeException e) {
            log.warn("文案任务失败后的状态回收没有完成: task={}", event.taskId(), e);
        }
    }
}
