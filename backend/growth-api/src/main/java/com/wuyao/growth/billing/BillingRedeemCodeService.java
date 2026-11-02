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
 * 烟火兑换码：计费运营批量生成、查看、作废；商家管理员或店员按完整码兑换，一码一用。
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

    /** 同一 requestKey 重复提交返回第一次生成的那批码。 */
    @Transactional
    public List<BillingDtos.RedeemCodeView> create(BillingDtos.NewRedeemCodes input, Long operator) {
        if (input.expiresAt() != null && !input.expiresAt().isAfter(Instant.now())) throw bad("失效时间须晚于现在");
        if (!"TOPUP".equals(input.kind()) && input.amountMinor() != 0) throw bad("赠送兑换码没有实收金额");
        em.createNativeQuery("SELECT true FROM pg_advisory_xact_lock(hashtextextended(:key,0))")
                .setParameter("key", "redeem-batch:" + input.requestKey()).getSingleResult();
        var existing = em.createQuery("from BillingRedeemCode c where c.batchKey = :key order by c.id", BillingRedeemCode.class)
                .setParameter("key", input.requestKey()).getResultList();
        if (existing.isEmpty()) {
            String note = input.note() == null || input.note().isBlank() ? null : input.note().trim();
            for (int i = 0; i < input.quantity(); i++) {
                var c = new BillingRedeemCode();
                c.setCode(generate()); c.setBatchKey(input.requestKey()); c.setKind(input.kind()); c.setCredits(input.credits()); c.setAmountMinor(input.amountMinor());
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
        if (page < 0 || size < 1 || size > 100) throw bad("分页参数无效");
        if (status != null && !status.isBlank() && !STATUSES.contains(status)) throw bad("筛选条件无效");
        if (kind != null && !kind.isBlank() && !Set.of("TOPUP", "GIFT").contains(kind)) throw bad("筛选条件无效");
        String where = " where (:kind = '' or c.kind = :kind) and (:key = '' or c.code like :code or lower(c.note) like :note)" + switch (status == null ? "" : status) {
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
        var count = em.createQuery("select count(c) from BillingRedeemCode c" + where, Long.class);
        var rows = em.createQuery("from BillingRedeemCode c" + where + " order by c.createdAt desc, c.id desc", BillingRedeemCode.class);
        params.forEach((name, value) -> { count.setParameter(name, value); rows.setParameter(name, value); });
        long total = count.getSingleResult();
        var items = views(rows.setFirstResult(page * size).setMaxResults(size).getResultList());
        return new PageResult<>(items, page, size, total, (int) Math.ceil((double) total / size));
    }

    @Transactional
    public BillingDtos.RedeemCodeView voidCode(Long id, Long operator) {
        var c = em.find(BillingRedeemCode.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (c == null) throw BizException.of(ErrorCode.NOT_FOUND, "兑换码不存在");
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
        long available = billing.creditRedeemCode(c.getId(), c.getKind(), c.getCredits(), c.getAmountMinor());
        return new BillingDtos.Redeemed(c.getKind(), c.getCredits(), available);
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
        Instant now = Instant.now();
        return codes.stream().map(c -> new BillingDtos.RedeemCodeView(c.getId(), c.getCode(), c.getKind(), c.getCredits(), c.getAmountMinor(),
                c.status(now), c.getNote(), c.getExpiresAt(), c.getCreatedAt(), c.getRedeemedTenantId(),
                c.getRedeemedTenantId() == null ? null : tenants.get(c.getRedeemedTenantId()), c.getRedeemedAt(), c.getVoidedAt())).toList();
    }

    private static BizException bad(String message) { return BizException.of(ErrorCode.BAD_REQUEST, message); }
}
