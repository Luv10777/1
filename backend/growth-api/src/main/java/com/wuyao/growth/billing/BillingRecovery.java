package com.wuyao.growth.billing;

import com.wuyao.growth.common.task.*;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.creative.image.ImageCreationService;
import com.wuyao.growth.iam.repository.TenantRepository;
import com.wuyao.growth.video.VideoWorkflowService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;

/** Replays terminal failures after a process crash or an after-commit listener error. */
@Component @RequiredArgsConstructor @Slf4j
public class BillingRecovery {
    private final TenantRepository tenants;
    private final EntityManager em;
    private final TransactionTemplate transactions;
    private final TaskRepository tasks;
    private final ImageCreationService images;
    private final VideoWorkflowService videos;
    private final BillingService billing;
    private final BillingCheckoutService checkout;
    private final WechatPayGateway wechat;
    @Scheduled(fixedDelayString="${growth.billing.recovery-interval-ms:60000}")
    public void recover() {
        Long after=0L;
        while(true) {
            var ids=tenants.idsAfter(after,PageRequest.of(0,100));
            if(ids.isEmpty()) return;
            for(Long tenant:ids) TenantContext.runAs(tenant,()->{
                try {
                    if(wechat.configured()) for(String id:billing.reconciliationOrders()) {
                        try { checkout.refresh(id); }
                        catch(RuntimeException e) { log.warn("微信订单对账等待重试: tenant={} order={}",tenant,id); }
                    }
                    List<TaskTerminalFailure> events=transactions.execute(status->terminalEvents());
                    for(var event:events) {
                        try {
                            if(event.type().startsWith("IMAGE_")) images.taskFailed(event);
                            else videos.taskFailed(event);
                        } catch(RuntimeException e) { log.warn("烟火释放等待重试: tenant={} task={}",tenant,event.taskId(),e); }
                    }
                } catch(RuntimeException e) { log.warn("烟火恢复检查失败: tenant={}",tenant,e); }
                return null;
            });
            after=ids.getLast();
        }
    }
    @SuppressWarnings("unchecked")
    private List<TaskTerminalFailure> terminalEvents() {
        List<Number> ids=em.createNativeQuery("""
            SELECT DISTINCT t.id FROM tasks t WHERE t.status IN ('FAILED','CANCELLED') AND (
              EXISTS (SELECT 1 FROM image_creations c JOIN billing_reservations r ON r.id=c.billing_reservation_id
                WHERE c.task_id=t.id AND c.plan IS NULL AND r.gift_held+r.purchased_held>0)
              OR EXISTS (SELECT 1 FROM image_items i WHERE i.task_id=t.id AND i.billing_held=true)
              OR EXISTS (SELECT 1 FROM video_workflows w JOIN billing_reservations r ON r.id=w.billing_reservation_id
                WHERE w.task_id=t.id AND w.status<>'SUCCEEDED' AND r.gift_held+r.purchased_held>0))
              ORDER BY t.id LIMIT 50
            """).getResultList();
        return ids.stream().map(id->tasks.findById(id.longValue()).orElseThrow()).map(TaskTerminalFailure::from).toList();
    }
}
