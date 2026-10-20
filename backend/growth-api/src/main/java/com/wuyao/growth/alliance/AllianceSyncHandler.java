package com.wuyao.growth.alliance;

import com.wuyao.growth.common.task.NonRetryableTaskException;
import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskHandler;
import com.wuyao.growth.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 把一边商户已经提交的结果同步到另一边。每种动作都可以重复执行，执行到一半失败由框架重试。
 * <ul>
 *   <li>CLAIM（领券方登记）：补完领券第 2、3 步</li>
 *   <li>REDEEMED（供券方登记）：领券方的券改「已核销」</li>
 *   <li>EXPIRE（供券方登记）：供券方作废到期的领取并还库存，再把领券方的券改「已过期」</li>
 *   <li>FREEZE（领券方登记）：申诉后冻结供券方的结算单</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AllianceSyncHandler implements TaskHandler {
    public static final String TYPE = "ALLIANCE_SYNC";
    static final String CLAIM = "CLAIM";
    static final String REDEEMED = "REDEEMED";
    static final String EXPIRE = "EXPIRE";
    static final String FREEZE = "FREEZE";

    private final AllianceClaimService claims;
    private final AllianceVoucherService vouchers;
    private final AllianceSettlementService settlements;

    @Override
    public String type() { return TYPE; }

    @Override
    public Map<String, Object> handle(Task task) {
        Map<String, Object> payload = task.getPayload();
        String action = String.valueOf(payload.get("action"));
        UUID ref = UUID.fromString(String.valueOf(payload.get("ref")));
        switch (action) {
            case CLAIM -> {
                var ticket = vouchers.pendingTicket(ref);
                if (ticket != null) vouchers.finishClaim(ref, claims.reserve(ticket));
            }
            case REDEEMED -> {
                Instant redeemedAt = Instant.parse(String.valueOf(payload.get("redeemedAt")));
                TenantContext.runAs(id(payload, "holderTenantId"), () -> { vouchers.markRedeemed(ref, redeemedAt); return null; });
            }
            case EXPIRE -> settlements.expire(ref).ifPresent(holder ->
                    TenantContext.runAs(holder, () -> { vouchers.markExpired(ref); return null; }));
            case FREEZE -> TenantContext.runAs(id(payload, "supplierTenantId"), () -> { settlements.freeze(ref); return null; });
            default -> throw new NonRetryableTaskException("ALLIANCE_UNKNOWN_ACTION", "未知的联盟券同步动作: " + action, null);
        }
        return Map.of("action", action, "ref", ref.toString());
    }

    private static Long id(Map<String, Object> payload, String key) {
        return ((Number) payload.get(key)).longValue();
    }
}
