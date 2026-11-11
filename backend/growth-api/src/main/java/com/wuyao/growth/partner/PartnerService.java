package com.wuyao.growth.partner;

import com.wuyao.growth.billing.BillingDtos;
import com.wuyao.growth.billing.BillingRedeemCodeService;
import com.wuyao.growth.billing.BillingService;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.common.web.PageResult;
import com.wuyao.growth.iam.service.PlatformAccountDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 服务商后台（partner.yifangzhi.com）：服务商账号查看自己的烟火和激活码额度，按 UID 添加名下商户，
 * 把烟火分给名下商户，在额度内生成激活码。每个接口先确认当前账号属于一家启用中的服务商，只能动这一家的数据。
 */
@Service
@RequiredArgsConstructor
public class PartnerService {
    private final PartnerStore store;
    private final PlatformAccountDirectory directory;
    private final BillingService billing;
    private final BillingRedeemCodeService codes;
    private final PlatformTransactionManager transactionManager;

    public PartnerDtos.Overview overview(Long userId) {
        var partner = requirePartner(userId);
        return new PartnerDtos.Overview(partner.view(), quotas(partner.id()), store.tenantCount(partner.id()));
    }

    public PartnerDtos.Page<PartnerDtos.TenantView> tenants(Long userId, int page, int size, String keyword) {
        var partner = requirePartner(userId);
        return tenantViews(store.tenants(partner.id()), page, size, keyword);
    }

    /** 按 UID 把该账号所属的商户加到名下。第一次归属为准：已归属别家服务商的不能加。 */
    public PartnerDtos.TenantView addTenant(Long userId, Long uid) {
        var partner = requirePartner(userId);
        var user = directory.userByUid(uid).filter(u -> !"REMOVED".equals(u.status()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "没有找到这个 UID，请核对后重新输入"));
        Long owner = store.bindIfAbsent(user.tenantId(), partner.id(), "UID", userId);
        if (!owner.equals(partner.id())) throw BizException.of(ErrorCode.CONFLICT, "该用户已归属其他服务商，不能添加");
        return tenantView(user.tenantId());
    }

    /**
     * 把服务商的烟火分给名下商户，记入商户的充值烟火。先在服务商这边扣减记账，再切到商户入账；
     * 两步各自按 requestKey 去重，商户入账失败时用同一 requestKey 再提交一次即可补上，不会重复扣减。
     */
    public PartnerDtos.TenantView transfer(Long userId, Long tenantId, PartnerDtos.Transfer input) {
        var partner = requirePartner(userId);
        var binding = store.binding(tenantId).filter(b -> b.partnerId().equals(partner.id()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "该商户不在你名下"));
        String key = "partner-transfer:" + partner.id() + ":" + input.requestKey();
        String note = blank(input.note()) ? null : input.note().trim();
        tx(false, () -> {
            var locked = store.lockPartner(partner.id());
            if (!store.eventExists(key)) store.move(locked, key, "TRANSFER", -input.credits(), binding.tenantId(), note, userId);
            return null;
        });
        inScope(binding.tenantId(), false, () -> billing.creditFromPartner(input.credits(), key, partner.name(), userId));
        return tenantView(binding.tenantId());
    }

    public PageResult<BillingDtos.RedeemCodeView> codes(Long userId, int page, int size, String status, String keyword) {
        var partner = requirePartner(userId);
        return codes.listForPartner(partner.id(), page, size, status, keyword);
    }

    /**
     * 在额度内生成激活码，按该套餐标识最新的版本开通。额度扣减和生成在同一事务；同一 requestKey 重复提交返回第一次那批，不再扣额度。
     */
    @Transactional
    public List<BillingDtos.RedeemCodeView> createCodes(Long userId, PartnerDtos.NewCodes input) {
        var partner = requirePartner(userId);
        var plan = billing.activationPlans().stream().filter(p -> p.code().equals(input.planCode())).findFirst()
                .orElseThrow(() -> bad("没有这个套餐，不能生成激活码"));
        var quota = store.lockQuota(partner.id(), input.planCode())
                .orElseThrow(() -> bad("平台还没有给你分配「" + plan.name() + "」的激活码额度"));
        String batch = "partner-" + partner.id() + "-" + input.requestKey();
        String note = blank(input.note()) ? null : input.note().trim();
        if (!codes.batchExists(batch)) {
            int remaining = quota.quota() - quota.used();
            if (remaining < input.quantity()) throw bad("「" + plan.name() + "」激活码额度不足，还能生成 " + remaining + " 个");
            store.useQuota(partner.id(), input.planCode(), input.quantity());
        }
        return codes.createForPartner(partner.id(), plan.id(), input.quantity(), batch, note, userId);
    }

    public PartnerDtos.Page<PartnerDtos.LedgerEntry> ledger(Long userId, int page, int size) {
        return ledgerOf(requirePartner(userId).id(), page, size);
    }

    PartnerStore.Row requirePartner(Long userId) {
        var partner = store.partnerOfUser(userId)
                .orElseThrow(() -> BizException.of(ErrorCode.FORBIDDEN, "这个账号还不是服务商账号，请联系平台开通"));
        if (!"ACTIVE".equals(partner.status())) throw BizException.of(ErrorCode.FORBIDDEN, "服务商已停用，请联系平台");
        return partner;
    }

    /** 额度按套餐标识列出，套餐名和天数取最新的版本。 */
    List<PartnerDtos.Quota> quotas(Long partnerId) {
        Map<String, BillingDtos.PlanSnapshot> plans = billing.activationPlans().stream()
                .collect(Collectors.toMap(BillingDtos.PlanSnapshot::code, p -> p, (a, b) -> a));
        return store.quotas(partnerId).stream().map(q -> {
            var plan = plans.get(q.planCode());
            return new PartnerDtos.Quota(q.planCode(), plan == null ? null : plan.name(), plan == null ? null : plan.durationDays(),
                    q.quota(), q.used(), q.quota() - q.used());
        }).toList();
    }

    PartnerDtos.Page<PartnerDtos.LedgerEntry> ledgerOf(Long partnerId, int page, int size) {
        checkPage(page, size);
        Map<Long, String> names = tenantNames();
        var items = store.ledger(partnerId, page, size).stream().map(l -> new PartnerDtos.LedgerEntry(l.id(), l.type(), l.delta(),
                l.availableAfter(), l.tenantId(), l.tenantId() == null ? null : names.get(l.tenantId()), l.note(), l.createdAt())).toList();
        return new PartnerDtos.Page<>(items, page, size, store.ledgerCount(partnerId));
    }

    /** 名下商户分页；会员和余额只为当前页的商户读取，每个商户在自己的租户里读一次。 */
    PartnerDtos.Page<PartnerDtos.TenantView> tenantViews(List<PartnerStore.Binding> bindings, int page, int size, String keyword) {
        checkPage(page, size);
        Map<Long, String> names = tenantNames();
        Map<Long, PlatformAccountDirectory.PlatformUser> owners = owners();
        String key = blank(keyword) ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        var matched = bindings.stream().filter(b -> key.isEmpty() || Stream.of(names.get(b.tenantId()), String.valueOf(b.tenantId()),
                        owners.get(b.tenantId()) == null ? null : owners.get(b.tenantId()).name(),
                        owners.get(b.tenantId()) == null ? null : String.valueOf(owners.get(b.tenantId()).uid()))
                .filter(Objects::nonNull).anyMatch(v -> v.toLowerCase(Locale.ROOT).contains(key))).toList();
        int start = (int) Math.min((long) page * size, matched.size()), end = Math.min(start + size, matched.size());
        var items = matched.subList(start, end).stream().map(b -> view(b, names, owners)).toList();
        return new PartnerDtos.Page<>(items, page, size, matched.size());
    }

    PartnerDtos.TenantView tenantView(Long tenantId) {
        var binding = store.binding(tenantId).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "该商户没有归属服务商"));
        return view(binding, tenantNames(), owners());
    }

    private PartnerDtos.TenantView view(PartnerStore.Binding b, Map<Long, String> names, Map<Long, PlatformAccountDirectory.PlatformUser> owners) {
        var owner = owners.get(b.tenantId());
        BillingDtos.Summary summary;
        try { summary = inScope(b.tenantId(), true, () -> billing.summary(false, false)); }
        catch (RuntimeException e) { summary = null; }
        return new PartnerDtos.TenantView(b.tenantId(), names.get(b.tenantId()), owner == null ? null : owner.uid(), owner == null ? null : owner.name(),
                b.source(), b.boundAt(), summary == null ? null : summary.memberActive(), summary == null ? null : summary.memberUntil(),
                summary == null || summary.plan() == null ? null : summary.plan().name(), summary == null ? null : summary.available());
    }

    /** 每个商户最早注册的管理员，作为商户的联系人展示。 */
    private Map<Long, PlatformAccountDirectory.PlatformUser> owners() {
        Map<Long, PlatformAccountDirectory.PlatformUser> owners = new HashMap<>();
        for (var u : directory.users()) {
            if (!"OWNER".equals(u.role()) || "REMOVED".equals(u.status())) continue;
            owners.merge(u.tenantId(), u, (a, b) -> a.createdAt().isBefore(b.createdAt()) ? a : b);
        }
        return owners;
    }

    Map<Long, String> tenantNames() {
        return directory.scopes().stream().collect(Collectors.toMap(PlatformAccountDirectory.Scope::id, s -> s.name() == null ? "" : s.name()));
    }

    private <T> T tx(boolean readOnly, Supplier<T> work) {
        var tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setReadOnly(readOnly);
        return tx.execute(status -> work.get());
    }

    /** 商户的账本受 RLS 约束：切到该商户后新开事务。 */
    private <T> T inScope(Long tenantId, boolean readOnly, Supplier<T> work) {
        return TenantContext.runAs(tenantId, () -> tx(readOnly, work));
    }

    static void checkPage(int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100) throw bad("分页参数无效");
    }

    static boolean blank(String value) { return value == null || value.isBlank(); }

    static BizException bad(String message) { return BizException.of(ErrorCode.BAD_REQUEST, message); }
}
