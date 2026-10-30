package com.wuyao.growth.referral;

import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 被邀请商户充值或开通会员付款后，在邀请人所在商户记一条返利。按订单号去重，至少执行一次也只有一条。 */
@Component
@RequiredArgsConstructor
public class ReferralCommissionHandler implements TaskHandler {
    public static final String TYPE = "REFERRAL_COMMISSION";
    private final ReferralService service;

    @Override public String type() { return TYPE; }

    @Override public Map<String, Object> handle(Task task) {
        var p = task.getPayload();
        boolean created = service.recordCommission(number(p, "referrerTenantId"), number(p, "referrerUserId"),
                // V72 之前登记的任务没有 kind，都是充值。
                number(p, "inviteeTenantId"), (String) p.get("orderId"), (String) p.getOrDefault("kind", ReferralService.TOPUP),
                number(p, "paidMinor"));
        return Map.of("status", created ? "CREATED" : "EXISTS");
    }

    private static long number(Map<String, Object> payload, String key) {
        return ((Number) payload.get(key)).longValue();
    }
}
