package com.wuyao.growth.billing;

import com.wuyao.growth.iam.service.AccountClosureGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 余额和会员属于商户，只在关闭整个商户时作废，注销前要让本人看清楚；还在等付款的订单要先处理掉，
 * 不然钱会付进一个已经关掉的商户。
 */
@Component
@RequiredArgsConstructor
public class BillingAccountClosure implements AccountClosureGuard {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("Asia/Shanghai"));
    private final BillingStore store;

    @Override
    public Optional<String> blocksClosing(Closure closure) {
        if (!closure.wholeTenant() || !store.awaitingPayment()) return Optional.empty();
        return Optional.of("有充值或会员订单还在等待付款，请先到“套餐与权益”里取消或付完，再注销");
    }

    @Override
    public List<String> losses(Closure closure) {
        BillingAccount account = closure.wholeTenant() ? store.account() : null;
        if (account == null) return List.of();
        List<String> losses = new ArrayList<>();
        if (account.available() + account.getHeld() > 0) {
            losses.add("商户剩余的 " + (account.available() + account.getHeld()) + " 烟火会作废，不退款");
        }
        if (account.active(Instant.now())) {
            losses.add("会员（" + DAY.format(account.getMemberUntil()) + " 到期）会作废，不退款");
        }
        return losses;
    }
}
