package com.wuyao.growth.copywriting;

import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;

/** 凌晨按商户登记的预生成任务：给这个商户最近活跃的门店各登记一份当天的「今日宜发」。 */
@Component
@RequiredArgsConstructor
public class CopyAlmanacPrepareHandler implements TaskHandler {
    public static final String TYPE = "COPY_ALMANAC_PREPARE";
    private final CopyAlmanacService service;

    @Override public String type() { return TYPE; }
    @Override public Map<String, Object> handle(Task task) {
        var day = LocalDate.parse((String) task.getPayload().get("day"));
        if (!day.equals(LocalDate.now(CopyAlmanacService.ZONE))) return Map.of("status", "STALE");
        return Map.of("status", "SUCCEEDED", "stores", service.prepareActive(day));
    }
}
