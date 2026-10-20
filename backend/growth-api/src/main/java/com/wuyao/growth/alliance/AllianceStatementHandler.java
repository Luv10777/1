package com.wuyao.growth.alliance;

import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.Map;

/**
 * 每天每个商户一条：生成上个月的对账单（已有就跳过），再把到期没处理的对账单自动确认。
 * 在该商户的租户里执行，可以重复执行。
 */
@Component
@RequiredArgsConstructor
public class AllianceStatementHandler implements TaskHandler {
    public static final String TYPE = "ALLIANCE_STATEMENT";
    private final AllianceStatementService statements;

    @Override
    public String type() { return TYPE; }

    @Override
    public Map<String, Object> handle(Task task) {
        YearMonth period = YearMonth.parse(String.valueOf(task.getPayload().get("period")));
        boolean generated;
        try {
            generated = statements.generate(period).isPresent();
        } catch (DataIntegrityViolationException concurrent) {
            generated = false; // 另一个执行已经生成了这个月的对账单
        }
        int confirmed = statements.autoConfirmDue();
        return Map.of("period", period.toString(), "generated", generated, "autoConfirmed", confirmed);
    }
}
