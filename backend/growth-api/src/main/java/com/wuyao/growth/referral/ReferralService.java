package com.wuyao.growth.referral;

import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.community.BankCardService;
import com.wuyao.growth.community.RealNameService;
import com.wuyao.growth.iam.service.PlatformAccountDirectory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 一代直推：新商户用邀请链接注册就永久归属邀请人，没有邀请码的归属公司。
 * 被邀请商户每次充值烟火或开通会员实际付款，按当时对应的平台比例给邀请人记现金返利，冻结 7 天后可提现（见 ReferralWithdrawalService）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReferralService {
    // 2026-10-31 写明提现规则和已提现返利作废后从下次提现扣回；每次申请提现都要同意当前版本。
    static final String AGREEMENT_VERSION = "2026-10-31";
    static final Duration FREEZE = Duration.ofDays(7);
    static final String TOPUP = "TOPUP";
    static final String MEMBERSHIP = "MEMBERSHIP";
    // 去掉容易看错的 0 O 1 I L。
    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ReferralCodeRepository codes;
    private final ReferralAttributionRepository attributions;
    private final ReferralInviteeRepository invitees;
    private final ReferralCommissionRepository commissions;
    private final ReferralRateRepository rates;
    private final ReferralStore store;
    private final RealNameService realNames;
    private final BankCardService bankCards;
    private final ReferralWithdrawalService withdrawals;
    private final PlatformAccountDirectory accounts;
    private final TaskService tasks;

    @Transactional(readOnly = true)
    public ReferralDtos.Overview overview(Long userId) {
        Long tenantId = TenantContext.require();
        Instant now = Instant.now();
        var inviteeRows = invitees.findTop100ByTenantIdAndReferrerUserIdOrderByRegisteredAtDesc(tenantId, userId);
        var commissionRows = commissions.findTop100ByTenantIdAndReferrerUserIdOrderByCreatedAtDesc(tenantId, userId);
        var phones = invitees.findAllById(commissionRows.stream().map(ReferralCommission::getInviteeTenantId).distinct().toList())
                .stream().collect(Collectors.toMap(ReferralInvitee::getInviteeTenantId, ReferralInvitee::getInviteePhoneMasked));
        return new ReferralDtos.Overview(
                verified(userId),
                codes.findById(userId).map(ReferralCode::getCode).orElse(null),
                AGREEMENT_VERSION, currentRateBp(TOPUP), currentRateBp(MEMBERSHIP), (int) FREEZE.toDays(),
                invitees.countByTenantIdAndReferrerUserId(tenantId, userId),
                commissions.sumFrozen(tenantId, userId, now),
                commissions.sumAvailable(tenantId, userId, now),
                commissions.sumOwed(tenantId, userId),
                ReferralWithdrawalService.MINIMUM_MINOR,
                bankCards.current(userId),
                inviteeRows.stream().map(i -> new ReferralDtos.Invitee(i.getInviteePhoneMasked(), i.getRegisteredAt())).toList(),
                commissionRows.stream().map(c -> new ReferralDtos.Commission(c.getId(), phones.getOrDefault(c.getInviteeTenantId(), "—"),
                        c.getOrderKind(), c.getPaidMinor(), c.getRateBp(), c.getAmountMinor(), state(c, now), c.getVoidNote(),
                        c.getAvailableAt(), c.getCreatedAt())).toList(),
                withdrawals.mine(userId));
    }

    /** 实名核验通过、同意《推广协议》后领邀请码。已经领过的直接返回。 */
    @Transactional
    public ReferralDtos.Overview createCode(Long userId) {
        if (!verified(userId)) throw BizException.of(ErrorCode.FORBIDDEN, "完成实名核验后才能获取邀请码");
        accounts.lockAccount(userId);
        if (codes.findById(userId).isEmpty()) {
            var row = new ReferralCode();
            row.setUserId(userId);
            row.setTenantId(TenantContext.require());
            row.setCode(newCode());
            row.setAgreementVersion(AGREEMENT_VERSION);
            codes.save(row);
        }
        return overview(userId);
    }

    /**
     * 新商户注册提交后调用。邀请码不存在或邀请人账号已停用时不报错，归属公司。
     * 两条记录分属两个商户，各自一个事务；重复调用不会多写。
     */
    public void bind(Long inviteeTenantId, String phone, String inviteCode) {
        if (!StringUtils.hasText(inviteCode)) return;
        String code = inviteCode.trim().toUpperCase(Locale.ROOT);
        var owner = codes.findOwner(code).filter(o -> Boolean.TRUE.equals(o.getActive())).orElse(null);
        if (owner == null || owner.getTenantId().equals(inviteeTenantId)) return;
        TenantContext.runAs(inviteeTenantId, () -> {
            store.saveAttribution(inviteeTenantId, owner.getUserId(), owner.getTenantId(), code);
            return null;
        });
        TenantContext.runAs(owner.getTenantId(), () -> {
            store.saveInvitee(owner.getTenantId(), owner.getUserId(), inviteeTenantId, maskPhone(phone));
            return null;
        });
        log.info("邀请注册归属: inviteeTenant={} referrerUser={}", inviteeTenantId, owner.getUserId());
    }

    /** 注册页手动填写邀请码时核对：只回答能不能用，不透露邀请人是谁。 */
    @Transactional(readOnly = true)
    public boolean usableCode(String inviteCode) {
        if (inviteCode == null || !inviteCode.trim().matches("[A-Za-z0-9]{4,16}")) return false;
        return codes.findOwner(inviteCode.trim().toUpperCase(Locale.ROOT)).filter(o -> Boolean.TRUE.equals(o.getActive())).isPresent();
    }

    /** 订单付款入账的同一事务里调用（当前商户就是付款商户）：有邀请人就登记一个记返利的任务，按订单号去重。 */
    public void onOrderPaid(Long tenantId, String orderId, String kind, long amountMinor) {
        if (!TOPUP.equals(kind) && !MEMBERSHIP.equals(kind)) return;
        attributions.findById(tenantId).ifPresent(a -> tasks.submit(ReferralCommissionHandler.TYPE, null, Map.of(
                "orderId", orderId, "kind", kind, "paidMinor", amountMinor, "inviteeTenantId", tenantId,
                "referrerTenantId", a.getReferrerTenantId(), "referrerUserId", a.getReferrerUserId()),
                "referral-commission:" + orderId, null));
    }

    /** 任务里调用：按此刻这类订单的平台比例，在邀请人所在商户记一条返利。 */
    boolean recordCommission(Long referrerTenantId, Long referrerUserId, Long inviteeTenantId, String orderId, String kind, long paidMinor) {
        int rateBp = currentRateBp(kind);
        return TenantContext.runAs(referrerTenantId, () -> store.saveCommission(referrerTenantId, referrerUserId, inviteeTenantId,
                orderId, kind, paidMinor, rateBp, Instant.now().plus(FREEZE)));
    }

    @Transactional(readOnly = true)
    public ReferralDtos.RateView rate() {
        return new ReferralDtos.RateView(currentRateBp(TOPUP), currentRateBp(MEMBERSHIP), rates.findTop10ByOrderByIdDesc().stream()
                .map(r -> new ReferralDtos.RateChange(r.getKind(), r.getRateBp(), r.getNote(), r.getCreatedBy(), r.getCreatedAt())).toList());
    }

    /** 新比例只影响之后到账的充值；已经记好的返利保留当时的比例。 */
    @Transactional
    public ReferralDtos.RateView updateRate(Long operatorId, ReferralDtos.UpdateRate request) {
        var row = new ReferralRate();
        row.setKind(request.kind());
        row.setRateBp(request.rateBp());
        row.setNote(request.note().trim());
        row.setCreatedBy(operatorId);
        rates.save(row);
        log.info("{} 返利比例调整为 {} bp，操作人 {}", request.kind(), request.rateBp(), operatorId);
        return rate();
    }

    int currentRateBp(String kind) {
        return rates.findTopByKindOrderByIdDesc(kind).map(ReferralRate::getRateBp).orElse(0);
    }

    private boolean verified(Long userId) {
        var application = realNames.current(userId);
        return application != null && "VERIFIED".equals(application.status());
    }

    private String newCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            var code = new StringBuilder(8);
            for (int i = 0; i < 8; i++) code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            if (codes.findOwner(code.toString()).isEmpty()) return code.toString();
        }
        throw new IllegalStateException("邀请码生成冲突过多");
    }

    static String state(ReferralCommission c, Instant now) {
        if ("VOIDED".equals(c.getStatus())) return "VOIDED";
        if (c.getWithdrawnAt() != null) return "WITHDRAWN";
        if (c.getWithdrawalId() != null) return "WITHDRAWING";
        return c.getAvailableAt().isAfter(now) ? "FROZEN" : "AVAILABLE";
    }

    static String maskPhone(String phone) {
        return phone != null && phone.length() == 11 ? phone.substring(0, 3) + "****" + phone.substring(7) : "—";
    }
}
