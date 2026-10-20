package com.wuyao.growth.alliance;

import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.iam.service.TenantDirectory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 只在 worker 进程运行：逐个商户找出已到期的领取记录，登记过期任务。这里不改业务数据，
 * 真正的作废、还库存和同步领券方由 {@link AllianceSyncHandler} 完成。同一张券的任务只登记一次。
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "growth.worker.enabled", havingValue = "true")
public class AllianceExpiryScheduler {
    private static final int BATCH = 100;
    private final TenantDirectory tenants;
    private final AllianceSettlementService settlements;
    private final TaskService tasks;

    @Scheduled(initialDelayString = "${growth.alliance.expiry-interval-ms:300000}",
            fixedDelayString = "${growth.alliance.expiry-interval-ms:300000}")
    public void scan() {
        Long after = 0L;
        while (true) {
            var ids = tenants.idsAfter(after);
            if (ids.isEmpty()) return;
            for (Long tenant : ids) {
                try {
                    TenantContext.runAs(tenant, () -> {
                        for (var ref : settlements.dueForExpiry(BATCH)) {
                            tasks.submit(AllianceSyncHandler.TYPE, null,
                                    Map.of("action", AllianceSyncHandler.EXPIRE, "ref", ref.toString()),
                                    "alliance-expire:" + ref, null);
                        }
                        return null;
                    });
                } catch (RuntimeException e) {
                    log.warn("联盟券到期扫描失败，下一轮重试: tenant={}", tenant, e);
                }
            }
            after = ids.getLast();
        }
    }
}
