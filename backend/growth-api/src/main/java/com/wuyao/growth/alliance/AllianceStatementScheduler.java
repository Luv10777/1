package com.wuyao.growth.alliance;

import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.iam.service.TenantDirectory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

/**
 * 只在 worker 进程运行：每天凌晨逐个商户登记一条对账任务（每商户每天一条）。
 * 每天都跑而不是只在 1 日跑，是为了 1 日没跑成时第二天能补上；生成本身已有就跳过。
 * 这里不改业务数据，生成和自动确认由 {@link AllianceStatementHandler} 完成。
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "growth.worker.enabled", havingValue = "true")
public class AllianceStatementScheduler {
    private final TenantDirectory tenants;
    private final TaskService tasks;

    @Scheduled(cron = "${growth.alliance.statement-cron:0 30 2 * * *}", zone = "Asia/Shanghai")
    public void schedule() {
        LocalDate today = LocalDate.now(AllianceStatementService.BUSINESS_ZONE);
        String period = YearMonth.from(today).minusMonths(1).toString();
        Long after = 0L;
        while (true) {
            var ids = tenants.idsAfter(after);
            if (ids.isEmpty()) return;
            for (Long tenant : ids) {
                try {
                    TenantContext.runAs(tenant, () -> tasks.submit(AllianceStatementHandler.TYPE, null,
                            Map.of("period", period), "alliance-statement:" + today, null));
                } catch (RuntimeException e) {
                    log.warn("联盟券对账任务登记失败，明天重试: tenant={}", tenant, e);
                }
            }
            after = ids.getLast();
        }
    }
}
