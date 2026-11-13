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

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 服务商后台（partner.yifangzhi.com）。登录账号分负责人（OWNER）和 BD：
 * 负责人看全部、分烟火、管 BD 和调配商户；BD 只看和管自己名下的商户与自己生成的激活码，不能分烟火。
 * 每个接口先确认当前账号属于一家启用中的服务商，只能动这一家的数据。
 */
@Service
@RequiredArgsConstructor
public class PartnerService {
    static final Set<String> EXPIRY_FILTERS = Set.of("SOON7", "SOON30", "EXPIRED", "NEVER");

    private final PartnerStore store;
    private final PlatformAccountDirectory directory;
    private final BillingService billing;
    private final BillingRedeemCodeService codes;
    private final PlatformTransactionManager transactionManager;

    public PartnerDtos.Overview overview(Long userId) {
        var access = requirePartner(userId);
        var p = access.partner();
        var partner = access.owner() ? p.view() : new PartnerDtos.Partner(p.id(), p.name(), p.contact(), p.status(), null, p.createdAt());
        return new PartnerDtos.Overview(partner, quotas(p.id()), visible(access).size(), access.role());
    }

    public PartnerDtos.Page<PartnerDtos.TenantView> tenants(Long userId, int page, int size, String keyword, String expiry, Long bdUserId) {
        var access = requirePartner(userId);
        var bindings = visible(access);
        // 负责人可以按 BD 筛（0 表示负责人直接管的）；BD 本来就只看得到自己的。
        if (access.owner() && bdUserId != null)
            bindings = bindings.stream().filter(b -> bdUserId == 0 ? b.bdUserId() == null : bdUserId.equals(b.bdUserId())).toList();
        return tenantViews(bindings, page, size, keyword, expiry);
    }

    /**
     * 按 UID 把该账号所属的商户加到名下，BD 加的归到这个 BD。第一次归属为准：已归属别家服务商的不能加；
     * 已在本服务商名下但不归这个 BD 的，要由负责人调配。
     */
    public PartnerDtos.TenantView addTenant(Long userId, Long uid) {
        var access = requirePartner(userId);
        var user = directory.userByUid(uid).filter(u -> !"REMOVED".equals(u.status()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "没有找到这个 UID，请核对后重新输入"));
        var binding = store.bindIfAbsent(user.tenantId(), access.partner().id(), "UID", userId, access.owner() ? null : userId);
        if (!binding.partnerId().equals(access.partner().id())) throw BizException.of(ErrorCode.CONFLICT, "该用户已归属其他服务商，不能添加");
        if (!access.owner() && !userId.equals(binding.bdUserId()))
            throw BizException.of(ErrorCode.CONFLICT, "该商户已在本服务商名下，请联系负责人调配给你");
        return tenantView(user.tenantId());
    }

    /**
     * 负责人把服务商的烟火分给名下商户，记入商户的充值烟火。先在服务商这边扣减记账，再切到商户入账；
     * 两步各自按 requestKey 去重，商户入账失败时用同一 requestKey 再提交一次即可补上，不会重复扣减。
     */
    public PartnerDtos.TenantView transfer(Long userId, Long tenantId, PartnerDtos.Transfer input) {
        var access = requireOwner(userId);
        var partner = access.partner();
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

    /** 负责人把名下商户调给某个 BD，或收回由自己直接管。 */
    @Transactional
    public PartnerDtos.TenantView assignBd(Long userId, Long tenantId, Long bdUserId) {
        var access = requireOwner(userId);
        Long partnerId = access.partner().id();
        store.lockPartner(partnerId);
        store.binding(tenantId).filter(b -> b.partnerId().equals(partnerId))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "该商户不在你名下"));
        if (bdUserId != null && store.role(partnerId, bdUserId).filter("BD"::equals).isEmpty())
            throw BizException.of(ErrorCode.NOT_FOUND, "该账号不是你的 BD");
        store.setBd(tenantId, partnerId, bdUserId);
        return tenantView(tenantId);
    }

    /** 负责人看团队：负责人和 BD 的登录账号，BD 带名下商户数。 */
    public List<PartnerDtos.Member> team(Long userId) {
        return members(requireOwner(userId).partner().id());
    }

    /** 负责人按 UID 添加 BD；一个账号最多属于一家服务商。 */
    @Transactional
    public List<PartnerDtos.Member> addBd(Long userId, Long uid) {
        var access = requireOwner(userId);
        Long partnerId = access.partner().id();
        store.lockPartner(partnerId);
        var user = directory.userByUid(uid).filter(u -> !"REMOVED".equals(u.status()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "没有找到这个 UID，请核对后重新输入"));
        store.addMember(partnerId, user.id(), "BD", userId).ifPresent(other -> {
            throw BizException.of(ErrorCode.CONFLICT, "该账号已是另一家服务商的登录账号");
        });
        if (store.role(partnerId, user.id()).filter("BD"::equals).isEmpty())
            throw BizException.of(ErrorCode.CONFLICT, "该账号已是本服务商的负责人");
        return members(partnerId);
    }

    /** 负责人移除 BD，BD 名下的商户回到负责人直接管。负责人账号只能由平台移除。 */
    @Transactional
    public List<PartnerDtos.Member> removeBd(Long userId, Long bdUserId) {
        var access = requireOwner(userId);
        Long partnerId = access.partner().id();
        store.lockPartner(partnerId);
        if (store.role(partnerId, bdUserId).filter("BD"::equals).isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND, "该账号不是你的 BD");
        store.removeMember(partnerId, bdUserId);
        return members(partnerId);
    }

    /** 激活码列表。BD 只看自己生成的；负责人可以按生成人筛。 */
    public PageResult<BillingDtos.RedeemCodeView> codes(Long userId, int page, int size, String status, String keyword, Long creator) {
        var access = requirePartner(userId);
        return codes.listForPartner(access.partner().id(), access.owner() ? creator : userId, page, size, status, keyword);
    }

    /**
     * 在额度内生成激活码，按该套餐标识最新的版本开通。额度扣减和生成在同一事务；同一 requestKey 重复提交返回第一次那批，不再扣额度。
     */
    @Transactional
    public List<BillingDtos.RedeemCodeView> createCodes(Long userId, PartnerDtos.NewCodes input) {
        var access = requirePartner(userId);
        return generate(access, input.planCode(), input.quantity(), "partner-" + access.partner().id() + "-" + input.requestKey(),
                blank(input.note()) ? null : input.note().trim(), userId);
    }

    /** 作废还没兑换的激活码，额度不退回。负责人能作废本服务商的码，BD 只能作废自己生成的。 */
    public BillingDtos.RedeemCodeView voidCode(Long userId, Long codeId) {
        var access = requirePartner(userId);
        return codes.voidForPartner(codeId, access.partner().id(), access.owner() ? null : userId, userId);
    }

    /**
     * 直接续期：用一个激活码额度给名下商户开通或顺延会员，不用把码发给商户再兑换。
     * 在商户的租户里一个事务做完扣额度、生成码、兑换；同一 requestKey 重复提交不再扣额度，也不重复顺延。
     * 生成的码备注「直接续期」，在激活码列表里能对账。
     */
    public PartnerDtos.TenantView renew(Long userId, Long tenantId, PartnerDtos.Renew input) {
        var access = requirePartner(userId);
        var binding = store.binding(tenantId).filter(b -> b.partnerId().equals(access.partner().id()))
                .filter(b -> access.owner() || userId.equals(b.bdUserId()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "该商户不在你名下"));
        String batch = "partner-renew-" + access.partner().id() + "-" + input.requestKey();
        inScope(binding.tenantId(), false, () -> {
            var code = generate(access, input.planCode(), 1, batch, "直接续期", userId).getFirst();
            if (!"REDEEMED".equals(code.status())) codes.redeem(code.code(), userId, binding.tenantId());
            else if (!binding.tenantId().equals(code.redeemedTenantId())) throw bad("续期请求已用于其他商户，请刷新后重试");
            return null;
        });
        return tenantView(binding.tenantId());
    }

    public PartnerDtos.Page<PartnerDtos.LedgerEntry> ledger(Long userId, int page, int size) {
        return ledgerOf(requireOwner(userId).partner().id(), page, size);
    }

    private List<BillingDtos.RedeemCodeView> generate(PartnerStore.Access access, String planCode, int quantity, String batch, String note, Long userId) {
        Long partnerId = access.partner().id();
        var plan = billing.activationPlans().stream().filter(p -> p.code().equals(planCode)).findFirst()
                .orElseThrow(() -> bad("没有这个套餐，不能生成激活码"));
        var quota = store.lockQuota(partnerId, planCode)
                .orElseThrow(() -> bad("平台还没有给你分配「" + plan.name() + "」的激活码额度"));
        if (!codes.batchExists(batch)) {
            int remaining = quota.quota() - quota.used();
            if (remaining < quantity) throw bad("「" + plan.name() + "」激活码额度不足，还能生成 " + remaining + " 个");
            store.useQuota(partnerId, planCode, quantity);
        }
        return codes.createForPartner(partnerId, plan.id(), quantity, batch, note, userId);
    }

    PartnerStore.Access requirePartner(Long userId) {
        var access = store.accessOfUser(userId)
                .orElseThrow(() -> BizException.of(ErrorCode.FORBIDDEN, "这个账号还不是服务商账号，请联系平台开通"));
        if (!"ACTIVE".equals(access.partner().status())) throw BizException.of(ErrorCode.FORBIDDEN, "服务商已停用，请联系平台");
        return access;
    }

    private PartnerStore.Access requireOwner(Long userId) {
        var access = requirePartner(userId);
        if (!access.owner()) throw BizException.of(ErrorCode.FORBIDDEN, "只有服务商负责人能做这项操作");
        return access;
    }

    /** 当前账号看得到的名下商户：负责人看全部，BD 只看归到自己的。 */
    private List<PartnerStore.Binding> visible(PartnerStore.Access access) {
        var all = store.tenants(access.partner().id());
        return access.owner() ? all : all.stream().filter(b -> access.userId().equals(b.bdUserId())).toList();
    }

    /** 服务商的登录账号，负责人在前；BD 带名下商户数。 */
    List<PartnerDtos.Member> members(Long partnerId) {
        Map<Long, Long> counts = store.tenants(partnerId).stream().filter(b -> b.bdUserId() != null)
                .collect(Collectors.groupingBy(PartnerStore.Binding::bdUserId, Collectors.counting()));
        return store.members(partnerId).stream().map(m -> {
            var u = directory.user(m.userId());
            return new PartnerDtos.Member(m.userId(), u == null ? null : u.uid(), u == null ? null : u.name(), u == null ? null : u.phone(),
                    m.role(), "BD".equals(m.role()) ? counts.getOrDefault(m.userId(), 0L) : null, m.createdAt());
        }).toList();
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

    /**
     * 名下商户分页。没有到期筛选时，会员和余额只为当前页的商户读取；按到期筛选时要读全部名下商户的会员状态
     * （每个商户在自己的租户里读一次），筛完按到期时间从早到晚排。
     */
    PartnerDtos.Page<PartnerDtos.TenantView> tenantViews(List<PartnerStore.Binding> bindings, int page, int size, String keyword, String expiry) {
        checkPage(page, size);
        if (!blank(expiry) && !EXPIRY_FILTERS.contains(expiry)) throw bad("筛选条件无效");
        Map<Long, String> names = tenantNames();
        Map<Long, PlatformAccountDirectory.PlatformUser> owners = owners();
        Map<Long, String> bdNames = new HashMap<>();
        String key = blank(keyword) ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        var matched = bindings.stream().filter(b -> key.isEmpty() || Stream.of(names.get(b.tenantId()), String.valueOf(b.tenantId()),
                        owners.get(b.tenantId()) == null ? null : owners.get(b.tenantId()).name(),
                        owners.get(b.tenantId()) == null ? null : String.valueOf(owners.get(b.tenantId()).uid()))
                .filter(Objects::nonNull).anyMatch(v -> v.toLowerCase(Locale.ROOT).contains(key))).toList();
        if (blank(expiry)) {
            int start = (int) Math.min((long) page * size, matched.size()), end = Math.min(start + size, matched.size());
            var items = matched.subList(start, end).stream().map(b -> view(b, names, owners, bdNames)).toList();
            return new PartnerDtos.Page<>(items, page, size, matched.size());
        }
        Instant now = Instant.now();
        var filtered = matched.stream().map(b -> view(b, names, owners, bdNames)).filter(v -> expiring(v, expiry, now))
                .sorted(Comparator.comparing(PartnerDtos.TenantView::memberUntil, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
        int start = (int) Math.min((long) page * size, filtered.size()), end = Math.min(start + size, filtered.size());
        return new PartnerDtos.Page<>(filtered.subList(start, end), page, size, filtered.size());
    }

    private static boolean expiring(PartnerDtos.TenantView v, String expiry, Instant now) {
        if (v.memberActive() == null) return false;
        return switch (expiry) {
            case "SOON7" -> v.memberActive() && v.memberUntil().isBefore(now.plus(Duration.ofDays(7)));
            case "SOON30" -> v.memberActive() && v.memberUntil().isBefore(now.plus(Duration.ofDays(30)));
            case "EXPIRED" -> !v.memberActive() && v.memberUntil() != null;
            default -> v.memberUntil() == null;
        };
    }

    PartnerDtos.TenantView tenantView(Long tenantId) {
        var binding = store.binding(tenantId).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "该商户没有归属服务商"));
        return view(binding, tenantNames(), owners(), new HashMap<>());
    }

    private PartnerDtos.TenantView view(PartnerStore.Binding b, Map<Long, String> names, Map<Long, PlatformAccountDirectory.PlatformUser> owners,
                                        Map<Long, String> bdNames) {
        var owner = owners.get(b.tenantId());
        BillingDtos.Summary summary;
        try { summary = inScope(b.tenantId(), true, () -> billing.summary(false, false)); }
        catch (RuntimeException e) { summary = null; }
        String bdName = b.bdUserId() == null ? null : bdNames.computeIfAbsent(b.bdUserId(), id -> {
            var u = directory.user(id);
            return u == null || u.name() == null ? "" : u.name();
        });
        return new PartnerDtos.TenantView(b.tenantId(), names.get(b.tenantId()), owner == null ? null : owner.uid(), owner == null ? null : owner.name(),
                b.source(), b.boundAt(), summary == null ? null : summary.memberActive(), summary == null ? null : summary.memberUntil(),
                summary == null || summary.plan() == null ? null : summary.plan().name(), summary == null ? null : summary.available(),
                b.bdUserId(), bdName);
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
