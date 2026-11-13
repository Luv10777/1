package com.wuyao.growth.billing;

import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.common.web.PageResult;
import com.wuyao.growth.iam.service.PlatformAccountDirectory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 烟火兑换码和激活码：计费运营批量生成、查看、作废；商家管理员或店员按完整码兑换，一码一用。
 * 激活码（MEMBERSHIP）兑换后按生成时的套餐版本开通或顺延会员，不送烟火。
 * 码长 16 位，取自去掉 0/O/1/I 的 32 个字符，约 80 位随机，无法靠猜。
 */
@Service
@RequiredArgsConstructor
public class BillingRedeemCodeService {
    static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    static final int CODE_LENGTH = 16;
    static final Set<String> STATUSES = Set.of("UNUSED", "REDEEMED", "VOIDED", "EXPIRED");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EntityManager em;
    private final BillingService billing;
    private final PlatformAccountDirectory directory;
    private final org.springframework.context.ApplicationEventPublisher events;

    /** 同一 requestKey 重复提交返回第一次生成的那批码。 */
    @Transactional
    public List<BillingDtos.RedeemCodeView> create(BillingDtos.NewRedeemCodes input, Long operator) {
        return create(input, operator, null);
    }

    /**
     * 服务商在额度内生成激活码，额度由服务商模块在同一事务里扣减。同一 requestKey 重复提交返回第一次那批，
     * 返回的码都属于这家服务商，别家的批次标识不会被借用。
     */
    @Transactional
    public List<BillingDtos.RedeemCodeView> createForPartner(Long partnerId, Long planId, int quantity, String requestKey, String note, Long actor) {
        var codes = create(new BillingDtos.NewRedeemCodes(requestKey, "MEMBERSHIP", 0, 0, quantity, null, note, planId), actor, partnerId);
        if (codes.isEmpty() || !partnerId.equals(codes.getFirst().partnerId())) throw bad("批次标识已被使用，请刷新后重新生成");
        return codes;
    }

    /** 该批次是否已经生成过（服务商模块据此判断重复提交，不重复扣额度）。 */
    @Transactional(readOnly = true)
    public boolean batchExists(String requestKey) {
        return !em.createQuery("select c.id from BillingRedeemCode c where c.batchKey = :key", Long.class)
                .setParameter("key", requestKey).setMaxResults(1).getResultList().isEmpty();
    }

    private List<BillingDtos.RedeemCodeView> create(BillingDtos.NewRedeemCodes input, Long operator, Long partnerId) {
        if (input.expiresAt() != null && !input.expiresAt().isAfter(Instant.now())) throw bad("失效时间须晚于现在");
        if (!"TOPUP".equals(input.kind()) && input.amountMinor() != 0) throw bad("只有充值兑换码有实收金额");
        boolean membership = "MEMBERSHIP".equals(input.kind());
        if (membership ? input.credits() != 0 : input.credits() < 1) throw bad(membership ? "激活码不送烟火" : "兑换码烟火须为 1 至 100000");
        if (membership != (input.planId() != null)) throw bad(membership ? "请选择激活码开通的套餐" : "只有激活码可以选择套餐");
        em.createNativeQuery("SELECT true FROM pg_advisory_xact_lock(hashtextextended(:key,0))")
                .setParameter("key", "redeem-batch:" + input.requestKey()).getSingleResult();
        var existing = em.createQuery("from BillingRedeemCode c where c.batchKey = :key order by c.id", BillingRedeemCode.class)
                .setParameter("key", input.requestKey()).getResultList();
        if (existing.isEmpty()) {
            // 激活码按套餐定开通天数；站内不卖、只发激活码的套餐不上架，所以不要求已上架。
            if (membership) billing.planForActivation(input.planId());
            String note = input.note() == null || input.note().isBlank() ? null : input.note().trim();
            for (int i = 0; i < input.quantity(); i++) {
                var c = new BillingRedeemCode();
                c.setCode(generate()); c.setBatchKey(input.requestKey()); c.setKind(input.kind()); c.setCredits(input.credits()); c.setAmountMinor(input.amountMinor());
                c.setPlanId(input.planId());
                c.setPartnerId(partnerId);
                c.setNote(note); c.setExpiresAt(input.expiresAt()); c.setCreatedBy(operator);
                em.persist(c);
                existing.add(c);
            }
            em.flush();
        }
        return views(existing);
    }

    @Transactional(readOnly = true)
    public PageResult<BillingDtos.RedeemCodeView> list(int page, int size, String status, String kind, String keyword) {
        return list(page, size, status, kind, keyword, 0L);
    }

    /** 某家服务商生成的激活码；creator 不为空时只列这个账号生成的（BD 只看自己生成的）。 */
    @Transactional(readOnly = true)
    public PageResult<BillingDtos.RedeemCodeView> listForPartner(Long partnerId, Long creator, int page, int size, String status, String keyword) {
        return list(page, size, status, "MEMBERSHIP", keyword, partnerId, creator);
    }

    private PageResult<BillingDtos.RedeemCodeView> list(int page, int size, String status, String kind, String keyword, long partnerId) {
        return list(page, size, status, kind, keyword, partnerId, null);
    }

    private PageResult<BillingDtos.RedeemCodeView> list(int page, int size, String status, String kind, String keyword, long partnerId, Long creator) {
        if (page < 0 || size < 1 || size > 100) throw bad("分页参数无效");
        if (status != null && !status.isBlank() && !STATUSES.contains(status)) throw bad("筛选条件无效");
        if (kind != null && !kind.isBlank() && !Set.of("TOPUP", "GIFT", "MEMBERSHIP").contains(kind)) throw bad("筛选条件无效");
        String where = " where (:kind = '' or c.kind = :kind) and (:key = '' or c.code like :code or lower(c.note) like :note)"
                + (partnerId > 0 ? " and c.partnerId = :partner" : "") + (creator != null ? " and c.createdBy = :creator" : "") + switch (status == null ? "" : status) {
            case "UNUSED" -> " and c.voidedAt is null and c.redeemedAt is null and (c.expiresAt is null or c.expiresAt > :now)";
            case "REDEEMED" -> " and c.redeemedAt is not null";
            case "VOIDED" -> " and c.voidedAt is not null";
            case "EXPIRED" -> " and c.voidedAt is null and c.redeemedAt is null and c.expiresAt <= :now";
            default -> "";
        };
        String key = keyword == null ? "" : keyword.trim(), codePart = normalize(key);
        // 关键词里没有字母数字时只按备注找，空的码片段不能匹配所有码。
        Map<String, Object> params = new HashMap<>(Map.of("kind", kind == null ? "" : kind, "key", key,
                "code", codePart.isEmpty() ? "" : "%" + codePart + "%", "note", "%" + key.toLowerCase(Locale.ROOT) + "%"));
        if (where.contains(":now")) params.put("now", Instant.now());
        if (partnerId > 0) params.put("partner", partnerId);
        if (creator != null) params.put("creator", creator);
        var count = em.createQuery("select count(c) from BillingRedeemCode c" + where, Long.class);
        var rows = em.createQuery("from BillingRedeemCode c" + where + " order by c.createdAt desc, c.id desc", BillingRedeemCode.class);
        params.forEach((name, value) -> { count.setParameter(name, value); rows.setParameter(name, value); });
        long total = count.getSingleResult();
        var items = views(rows.setFirstResult(page * size).setMaxResults(size).getResultList());
        return new PageResult<>(items, page, size, total, (int) Math.ceil((double) total / size));
    }

    /** 某个商户兑换过的、这家服务商生成的激活码（含直接续期），新的在前，最多 50 个。 */
    @Transactional(readOnly = true)
    public List<BillingDtos.RedeemCodeView> redeemedForPartner(Long partnerId, Long tenantId) {
        return views(em.createQuery("from BillingRedeemCode c where c.partnerId = :p and c.redeemedTenantId = :t order by c.redeemedAt desc, c.id desc",
                BillingRedeemCode.class).setParameter("p", partnerId).setParameter("t", tenantId).setMaxResults(50).getResultList());
    }

    @Transactional
    public BillingDtos.RedeemCodeView voidCode(Long id, Long operator) {
        return voidCode(id, operator, null, null);
    }

    /**
     * 服务商作废自己生成、还没兑换的激活码，额度不退回。creator 不为空时只能作废这个账号生成的（BD 只能作废自己的）。
     */
    @Transactional
    public BillingDtos.RedeemCodeView voidForPartner(Long id, Long partnerId, Long creator, Long operator) {
        return voidCode(id, operator, partnerId, creator);
    }

    private BillingDtos.RedeemCodeView voidCode(Long id, Long operator, Long partnerId, Long creator) {
        var c = em.find(BillingRedeemCode.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (c == null || partnerId != null && !partnerId.equals(c.getPartnerId())
                || creator != null && !creator.equals(c.getCreatedBy())) throw BizException.of(ErrorCode.NOT_FOUND, "兑换码不存在");
        switch (c.status(Instant.now())) {
            case "REDEEMED" -> throw BizException.of(ErrorCode.CONFLICT, "该兑换码已被兑换，不能作废");
            case "VOIDED" -> { return views(List.of(c)).getFirst(); }
            default -> { }
        }
        c.setVoidedAt(Instant.now()); c.setVoidedBy(operator);
        return views(List.of(c)).getFirst();
    }

    /** 当前商户兑换。先锁账户再锁码，与其他计费写入的加锁顺序一致。 */
    @Transactional
    public BillingDtos.Redeemed redeem(String raw, Long userId, Long tenantId) {
        String code = normalize(raw);
        if (code.length() != CODE_LENGTH) throw bad("兑换码无效，请核对后重新输入");
        billing.lockForGeneration();
        var c = em.createQuery("from BillingRedeemCode c where c.code = :code", BillingRedeemCode.class)
                .setParameter("code", code).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst()
                .orElseThrow(() -> bad("兑换码无效，请核对后重新输入"));
        switch (c.status(Instant.now())) {
            case "VOIDED" -> throw bad("该兑换码已作废");
            case "EXPIRED" -> throw bad("该兑换码已过期");
            case "REDEEMED" -> throw bad(tenantId.equals(c.getRedeemedTenantId()) ? "你已兑换过这个兑换码" : "该兑换码已被使用");
            default -> { }
        }
        c.setRedeemedTenantId(tenantId); c.setRedeemedBy(userId); c.setRedeemedAt(Instant.now());
        if ("MEMBERSHIP".equals(c.getKind())) {
            var activated = billing.activateRedeemCode(c.getId(), c.getPlanId());
            if (c.getPartnerId() != null) events.publishEvent(new BillingDtos.PartnerCodeRedeemed(tenantId, c.getId(), c.getPartnerId()));
            return activated;
        }
        long available = billing.creditRedeemCode(c.getId(), c.getKind(), c.getCredits(), c.getAmountMinor());
        return new BillingDtos.Redeemed(c.getKind(), c.getCredits(), available, null, null);
    }

    /** 与激活码订单入账同一事务：按订单生成一个激活码，同一订单只生成一次。 */
    @org.springframework.context.event.EventListener
    public void onActivationCodePurchased(BillingDtos.ActivationCodePurchased event) {
        if (!em.createQuery("select c.id from BillingRedeemCode c where c.orderId = :order", Long.class)
                .setParameter("order", event.orderId()).getResultList().isEmpty()) return;
        var c = new BillingRedeemCode();
        c.setCode(generate()); c.setBatchKey("order-" + event.orderId()); c.setKind("MEMBERSHIP"); c.setCredits(0); c.setAmountMinor(0);
        c.setPlanId(event.planId()); c.setNote("站内购买"); c.setCreatedBy(event.buyerUserId());
        c.setOrderId(event.orderId()); c.setPurchasedTenantId(event.tenantId());
        em.persist(c);
        em.flush();
    }

    /** 本商户在站内买的激活码，新买的在前。 */
    @Transactional(readOnly = true)
    public List<BillingDtos.PurchasedCode> purchased(Long tenantId) {
        var codes = em.createQuery("from BillingRedeemCode c where c.purchasedTenantId = :tenant order by c.createdAt desc, c.id desc", BillingRedeemCode.class)
                .setParameter("tenant", tenantId).setMaxResults(200).getResultList();
        Map<Long, BillingPlan> plans = new HashMap<>();
        Instant now = Instant.now();
        return codes.stream().map(c -> {
            var plan = plans.computeIfAbsent(c.getPlanId(), billing::planForActivation);
            return new BillingDtos.PurchasedCode(c.getCode(), plan.getName(), plan.getDurationDays(), c.status(now), c.getCreatedAt(),
                    c.getRedeemedAt(), c.getOrderId());
        }).toList();
    }

    /** 去掉空格、横线等分隔符并转大写，商家照抄 XXXX-XXXX-XXXX-XXXX 也能兑换。 */
    static String normalize(String raw) {
        return raw == null ? "" : raw.toUpperCase(Locale.ROOT).replaceAll("[^0-9A-Z]", "");
    }

    private static String generate() {
        var chars = new char[CODE_LENGTH];
        for (int i = 0; i < CODE_LENGTH; i++) chars[i] = ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length()));
        return new String(chars);
    }

    private List<BillingDtos.RedeemCodeView> views(List<BillingRedeemCode> codes) {
        Map<Long, String> tenants = codes.stream().anyMatch(c -> c.getRedeemedTenantId() != null)
                ? directory.scopes().stream().collect(Collectors.toMap(PlatformAccountDirectory.Scope::id, s -> String.valueOf(s.name())))
                : Map.of();
        Map<Long, BillingPlan> plans = new HashMap<>();
        for (var c : codes) if (c.getPlanId() != null) plans.computeIfAbsent(c.getPlanId(), billing::planForActivation);
        Instant now = Instant.now();
        return codes.stream().map(c -> {
            var plan = c.getPlanId() == null ? null : plans.get(c.getPlanId());
            return new BillingDtos.RedeemCodeView(c.getId(), c.getCode(), c.getKind(), c.getCredits(), c.getAmountMinor(),
                    c.status(now), c.getNote(), c.getExpiresAt(), c.getCreatedAt(), c.getRedeemedTenantId(),
                    c.getRedeemedTenantId() == null ? null : tenants.get(c.getRedeemedTenantId()), c.getRedeemedAt(), c.getVoidedAt(),
                    c.getPlanId(), plan == null ? null : plan.getName(), plan == null ? null : plan.getDurationDays(), c.getPartnerId(),
                    c.getCreatedBy());
        }).toList();
    }

    private static BizException bad(String message) { return BizException.of(ErrorCode.BAD_REQUEST, message); }
}
