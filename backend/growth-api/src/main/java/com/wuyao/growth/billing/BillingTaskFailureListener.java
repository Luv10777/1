package com.wuyao.growth.billing;

import com.wuyao.growth.common.task.TaskTerminalFailure;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.creative.image.ImageCreationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component @RequiredArgsConstructor @lombok.extern.slf4j.Slf4j
public class BillingTaskFailureListener {
    private final ImageCreationService images;
    private final com.wuyao.growth.video.VideoWorkflowService videos;
    private final com.wuyao.growth.video.analysis.VideoAnalysisService analyses;
    private final com.wuyao.growth.video.plan.VideoPlanService plans;
    @TransactionalEventListener(fallbackExecution=true)
    public void failed(TaskTerminalFailure event) {
        try {
        if(event.type().startsWith("IMAGE_")) TenantContext.runAs(event.tenantId(),()->{ images.taskFailed(event); return null; });
        if(event.type().startsWith("VIDEO_") && event.payload().containsKey("workflowId")) TenantContext.runAs(event.tenantId(),()->{ videos.taskFailed(event); return null; });
        if(event.type().startsWith("VIDEO_ANALYSIS_") && event.payload().containsKey("analysisId")) TenantContext.runAs(event.tenantId(),()->{ analyses.taskFailed(event); return null; });
        if(event.type().startsWith("VIDEO_PLAN_") && event.payload().containsKey("planId")) TenantContext.runAs(event.tenantId(),()->{ plans.taskFailed(event); return null; });
        } catch(RuntimeException e) { log.warn("任务已终止，烟火释放由恢复任务重试: task={}",event.taskId(),e); }
    }
}
