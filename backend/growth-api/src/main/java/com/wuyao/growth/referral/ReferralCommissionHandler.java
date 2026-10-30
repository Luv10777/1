package com.wuyao.growth.referral;

import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 被邀请商户充值到账后，在邀请人所在商户记一条返利。按订单号去重，至少执行一次也只有一条。 */
@Component
@RequiredArgsConstructor
public class ReferralCommissionHandler implements TaskHandler {
    public static final String TYPE = "REFERRAL_COMMISSION";
    private final ReferralService service;

    @Override public String type() { return TYPE; }

    @Override public Map<String, Object> handle(Task task) {
        var p = task.getPayload();
        boolean created = service.recordCommission(number(p, "referrerTenantId"), number(p, "referrerUserId"),
                number(p, "inviteeTenantId"), (String) p.get("orderId"), number(p, "paidMinor"));
        return Map.of("status", created ? "CREATED" : "EXISTS");
    }

    private static long number(Map<String, Object> payload, String key) {
        return ((Number) payload.get(key)).longValue();
    }
}
