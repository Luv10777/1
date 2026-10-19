package com.wuyao.growth.live;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.iam.service.TenantDirectory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每分钟检查一次正在直播的场次：快用完预占的一小时就续占下一小时，续不上就暂停。
 * 只在 worker 进程运行。多个 worker 同时检查也安全：账户和场次都加了行锁，预占用稳定的事件键。
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "growth.worker.enabled", havingValue = "true")
public class LiveBillingScheduler {
    private final TenantDirectory tenants;
    private final LiveSessionService sessions;

    @Scheduled(fixedDelayString = "${growth.live.billing-interval-ms:60000}")
    public void meter() {
        Long after = 0L;
        while (true) {
            var ids = tenants.idsAfter(after);
            if (ids.isEmpty()) return;
            for (Long tenant : ids) {
                TenantContext.runAs(tenant, () -> {
                    for (Long session : sessions.onAirIds(tenant)) {
                        try {
                            if (!sessions.meter(session)) log.info("直播烟火不足或会员失效，已暂停: tenant={} session={}", tenant, session);
                        } catch (RuntimeException e) {
                            log.warn("直播计费检查失败，下一轮重试: tenant={} session={}", tenant, session, e);
                        }
                    }
                    return null;
                });
            }
            after = ids.getLast();
        }
    }
}
