package com.wuyao.growth.copywriting;

import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.iam.service.TenantDirectory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;

/**
 * 每天凌晨给每个商户登记一个「今日宜发」预生成任务，商家早上打开首页时内容已经准备好。
 * 这里只登记任务，同一天重复执行不会重复生成。
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "growth.worker.enabled", havingValue = "true")
public class CopyAlmanacScheduler {
    private final TenantDirectory tenants;
    private final TaskService tasks;

    @Scheduled(cron = "${growth.copy.almanac-cron:0 10 0 * * *}", zone = "Asia/Shanghai")
    public void schedule() {
        String day = LocalDate.now(CopyAlmanacService.ZONE).toString();
        Long after = 0L;
        while (true) {
            var ids = tenants.idsAfter(after);
            if (ids.isEmpty()) return;
            for (Long tenant : ids) {
                try {
                    TenantContext.runAs(tenant, () -> tasks.submit(CopyAlmanacPrepareHandler.TYPE, "DEFAULT",
                            Map.of("day", day), "copy-almanac-prepare:" + day, null));
                } catch (RuntimeException e) {
                    log.warn("今日宜发预生成登记失败，商家打开首页时会再生成: tenant={}", tenant, e);
                }
            }
            after = ids.getLast();
        }
    }
}
