package com.wuyao.growth.billing;

import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.creative.image.ImageCreationService;
import com.wuyao.growth.iam.service.TenantDirectory;
import com.wuyao.growth.video.VideoWorkflowService;
import com.wuyao.growth.video.analysis.VideoAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.ArrayList;

/** Replays terminal failures through public module services after a lost failure event. */
@Component @RequiredArgsConstructor @Slf4j
public class BillingRecovery {
    private final TenantDirectory tenants;
    private final TaskService tasks;
    private final ImageCreationService images;
    private final VideoWorkflowService videos;
    private final VideoAnalysisService analyses;
    private final BillingService billing;
    private final BillingCheckoutService checkout;
    public void recover() {
        Long after=0L;
        while(true) {
            var ids=tenants.idsAfter(after);
            if(ids.isEmpty()) return;
            for(Long tenant:ids) TenantContext.runAs(tenant,()->{
                try {
                    if(!billing.claimRecovery()) return null;
                    if(checkout.reconciles()) for(String id:billing.reconciliationOrders()) {
                        try { checkout.refresh(id); }
                        catch(RuntimeException e) { log.warn("在线支付订单对账等待重试: tenant={} order={}",tenant,id); }
                    }
                    recoverFailures(tenant);
                } catch(RuntimeException e) { log.warn("烟火恢复检查失败: tenant={}",tenant,e); }
                return null;
            });
            after=ids.getLast();
        }
    }
    private void recoverFailures(Long tenant) {
        String after="";
        while(true) {
            var reservations=billing.unsettledReservations(after);
            if(reservations.isEmpty()) return;
            var ids=new ArrayList<Long>(images.billingRecoveryTaskIds(reservations));
            ids.addAll(videos.billingRecoveryTaskIds(reservations));
            ids.addAll(analyses.billingRecoveryTaskIds(reservations));
            for(var event:tasks.terminalFailures(ids.stream().distinct().toList())) {
                try {
                    if(event.type().startsWith("IMAGE_")) images.taskFailed(event);
                    else if(event.type().startsWith("VIDEO_ANALYSIS_")) analyses.taskFailed(event);
                    else if(event.type().startsWith("VIDEO_")) videos.taskFailed(event);
                } catch(RuntimeException e) { log.warn("烟火释放等待重试: tenant={} task={}",tenant,event.taskId(),e); }
            }
            after=reservations.getLast();
        }
    }
}
