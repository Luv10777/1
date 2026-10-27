package com.wuyao.growth.live;

import com.wuyao.growth.billing.BillingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 直播按「直播中」的时长计费，暂停不计。开播先预占一小时烟火，快用完时再预占下一小时；
 * 结束时按分钟结算（每分钟 35/60 烟火，向上取整），没用完的退回。
 * 调用方都在同一事务里，并且先锁账户、再锁场次，和烟火模块的加锁顺序一致。
 */
@Component
@RequiredArgsConstructor
public class LiveBilling {
    static final long HOUR_SECONDS = 3600;
    /** 当前这一小时剩下不到 5 分钟时预占下一小时，留出续占失败后暂停的时间。 */
    static final long RENEW_AHEAD_SECONDS = 300;
    /** 续占失败后，预占的时长剩下不到 2 分钟（两轮检查）就暂停，避免超出已预占的部分。 */
    static final long STOP_MARGIN_SECONDS = 120;

    private final BillingService billing;

    void lockAccount() {
        billing.lockForGeneration();
    }

    /** 开播或恢复：没有会员权限或烟火不足时抛出业务异常，调用方的事务随之回滚。 */
    void startAir(LiveSession session, Instant now) {
        if (session.getBillingReservationId() == null) {
            session.setBillingReservationId(billing.reserve("live:" + session.getId(), "LIVE",
                    BillingService.LIVE_CREDITS_PER_HOUR, "直播「" + session.getName() + "」· 预占 1 小时"));
            session.setBilledHours(1);
        } else {
            billing.requireMembership("LIVE");
            coverUntil(session, session.getLiveSeconds() + RENEW_AHEAD_SECONDS);
        }
        session.setLiveSegmentStartedAt(now);
    }

    /** 暂停或结束：把当前这段直播时长计入累计。 */
    void stopAir(LiveSession session, Instant now) {
        session.setLiveSeconds(onAirSeconds(session, now));
        session.setLiveSegmentStartedAt(null);
    }

    /**
     * 定时检查正在直播的场次，需要时预占下一小时。返回 false 表示无法继续，调用方应暂停本场。
     * 上线前就已开播、还没有预占的场次，从这次检查开始计费。
     */
    boolean renew(LiveSession session, Instant now) {
        long onAir = onAirSeconds(session, now);
        if (session.getBillingReservationId() == null) {
            if (!billing.canReserve("LIVE", BillingService.LIVE_CREDITS_PER_HOUR)) return false;
            startAir(session, now);
            return true;
        }
        while ((long) session.getBilledHours() * HOUR_SECONDS < onAir + RENEW_AHEAD_SECONDS) {
            // 先问再预占：预占失败的异常会把整个事务标记为回滚，暂停也就写不进去了。
            if (!billing.canReserve("LIVE", BillingService.LIVE_CREDITS_PER_HOUR)) {
                return onAir + STOP_MARGIN_SECONDS < (long) session.getBilledHours() * HOUR_SECONDS;
            }
            reserveNextHour(session);
        }
        return true;
    }

    /** 结束后按分钟结算，超出预占的部分由平台承担，剩余预占全部退回。 */
    void settle(LiveSession session) {
        String reservation = session.getBillingReservationId();
        if (reservation == null) return;
        long minutes = minutes(session.getLiveSeconds());
        long charge = Math.min(charge(session.getLiveSeconds()),
                (long) session.getBilledHours() * BillingService.LIVE_CREDITS_PER_HOUR);
        if (charge > 0) {
            billing.capture(reservation, charge, "live-capture:" + session.getId(),
                    "直播「" + session.getName() + "」· " + minutes + " 分钟");
        }
        billing.release(reservation, "live-release:" + session.getId(), "直播结束 · 退回未用烟火");
    }

    static LiveDtos.Billing view(LiveSession session, Instant now) {
        long seconds = onAirSeconds(session, now);
        long reserved = session.getBillingReservationId() == null ? 0
                : (long) session.getBilledHours() * BillingService.LIVE_CREDITS_PER_HOUR;
        long used = Math.min(charge(seconds), reserved);
        boolean ended = "ENDED".equals(session.getStatus());
        return new LiveDtos.Billing(BillingService.LIVE_CREDITS_PER_HOUR, seconds,
                session.getLiveSegmentStartedAt() != null, reserved, used, ended ? reserved - used : 0);
    }

    static long onAirSeconds(LiveSession session, Instant now) {
        Instant segment = session.getLiveSegmentStartedAt();
        long current = segment == null ? 0 : Math.max(0, Duration.between(segment, now).getSeconds());
        return session.getLiveSeconds() + current;
    }

    static long minutes(long seconds) {
        return Math.ceilDiv(seconds, 60);
    }

    /** 每分钟 35/60 烟火，整场向上取整。 */
    static long charge(long seconds) {
        return Math.ceilDiv(minutes(seconds) * BillingService.LIVE_CREDITS_PER_HOUR, 60);
    }

    private void coverUntil(LiveSession session, long seconds) {
        while ((long) session.getBilledHours() * HOUR_SECONDS < seconds) reserveNextHour(session);
    }

    private void reserveNextHour(LiveSession session) {
        int next = session.getBilledHours() + 1;
        billing.reserveMore(session.getBillingReservationId(), BillingService.LIVE_CREDITS_PER_HOUR,
                "live-hour:" + session.getId() + ":" + next,
                "直播「" + session.getName() + "」· 预占第 " + next + " 小时");
        session.setBilledHours(next);
    }
}
