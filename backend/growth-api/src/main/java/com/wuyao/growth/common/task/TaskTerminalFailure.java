package com.wuyao.growth.common.task;

import java.util.Map;
public record TaskTerminalFailure(Long tenantId,Long taskId,String type,Map<String,Object> payload) {
    public static TaskTerminalFailure from(Task t) { return new TaskTerminalFailure(t.getTenantId(),t.getId(),t.getType(),t.getPayload()); }
}
