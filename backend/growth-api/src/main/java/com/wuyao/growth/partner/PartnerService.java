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
    private final com.wuyao.growth.billing.BillingLedgerReport usage;
    private final com.wuyao.growth.store.StoreService stores;
    private final com.wuyao.growth.product.ProductService products;
    private final PlatformTransactionManager transactionManager;

    public PartnerDtos.Overview overview(Long userId) {
        var access = requirePartner(userId);
        var p = access.partner();
        var partner = access.owner() ? p.view() : new PartnerDtos.Partner(p.id(), p.name(), p.contact(), p.status(), null, p.createdAt());
        return new PartnerDtos.Overview(partner, quotas(p.id()), visible(access).size(), access.role(), account(userId));
    }

    /** 当前登录账号在一方志里的烟火（负责人和 BD 都是自己的账号），在账号所在的商户里读。 */
    PartnerDtos.Account account(Long userId) {
        var s = inScope(directory.user(userId).tenantId(), true, () -> billing.summary(false, false));
        return new PartnerDtos.Account(s.available(), s.giftAvailable(), s.purchasedAvailable());
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
        var binding = store.binding(tenantId).filter(b -> b.partnerId().equals(access.partner().id()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "该商户不在你名下"));
        distribute(access, input, "TENANT", binding.tenantId(), null);
        return tenantView(binding.tenantId());
    }

    /** 负责人把烟火分给自己的 BD，进 BD 自己的一方志账号。 */
    public PartnerDtos.Distribution transferToBd(Long userId, Long bdUserId, PartnerDtos.Transfer input) {
        var access = requireOwner(userId);
        if (store.role(access.partner().id(), bdUserId).filter("BD"::equals).isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND, "该账号不是你的 BD");
        var record = distribute(access, input, "BD", directory.user(bdUserId).tenantId(), bdUserId);
        return distributionViews(List.of(record)).getFirst();
    }

    /**
     * 分一笔烟火：先从来源扣（可分配烟火记服务商流水；负责人账号记一条转出，先扣赠送），记下分配，再给对方入账，最后标记到账。
     * 每一步都按 requestKey 去重：中途失败时用同一 requestKey 再提交，已经做过的步骤不会重复。
     * 负责人账号分出的赠送烟火对方收到仍是赠送烟火，充值仍是充值；可分配烟火对方收到的是充值烟火。
     */
    private PartnerStore.Distribution distribute(PartnerStore.Access access, PartnerDtos.Transfer input, String recipientType,
                                                 Long recipientTenant, Long recipientUser) {
        var partner = access.partner();
        Long actor = access.userId();
        String source = blank(input.source()) ? "POOL" : input.source();
        String key = "partner-transfer:" + partner.id() + ":" + input.requestKey();
        String note = blank(input.note()) ? null : input.note().trim();
        var existing = store.distributionByKey(key);
        if (existing.isPresent()) {
            var d = existing.get();
            if (!d.source().equals(source) || !d.recipientTenantId().equals(recipientTenant) || d.credits() != input.credits()
                    || !Objects.equals(d.recipientUserId(), recipientUser))
                throw bad("分配请求已用于其他对象或数量，请刷新后重试");
            if ("DONE".equals(d.status())) return d;
        }
        Long ownTenant = directory.user(actor).tenantId();
        if ("ACCOUNT".equals(source) && ownTenant.equals(recipientTenant))
            throw bad("对方和你用的是同一个商户账号，烟火本来就共用，不用分");
        String who = "服务商「" + partner.name() + "」";
        BillingDtos.CreditSplit split;
        if ("POOL".equals(source)) {
            tx(false, () -> {
                var locked = store.lockPartner(partner.id());
                if (!store.eventExists(key)) store.move(locked, key, "TRANSFER", -input.credits(), recipientTenant, note, actor);
                return null;
            });
            split = new BillingDtos.CreditSplit(0, input.credits());
        } else {
            String target = "BD".equals(recipientType) ? "BD「" + userName(recipientUser) + "」" : "商户「" + tenantNames().get(recipientTenant) + "」";
            split = inScope(ownTenant, false, () -> billing.debitForPartner(input.credits(), key, "分配给" + target, actor));
        }
        tx(false, () -> {
            store.recordDistribution(partner.id(), key, source, recipientType, recipientTenant, recipientUser, split.gift(), split.purchased(), note, actor);
            return null;
        });
        if ("POOL".equals(source)) inScope(recipientTenant, false, () -> billing.creditFromPartner(input.credits(), key, partner.name(), actor));
        else inScope(recipientTenant, false, () -> billing.creditFromPartnerAccount(split, key, who + "负责人分配烟火", actor));
        tx(false, () -> { store.completeDistribution(key); return null; });
        return store.distributionByKey(key).orElseThrow();
    }

    /** 补上一笔「未到账」的分配：用原来的请求再走一遍，已经做过的步骤不会重复，不会再扣一次。 */
    public PartnerDtos.Distribution retryDistribution(Long userId, Long id) {
        var access = requireOwner(userId);
        var d = store.distribution(access.partner().id(), id).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "分配记录不存在"));
        if ("DONE".equals(d.status())) return distributionViews(List.of(d)).getFirst();
        String prefix = "partner-transfer:" + access.partner().id() + ":";
        var input = new PartnerDtos.Transfer(d.requestKey().substring(prefix.length()), d.credits(), d.note(), d.source());
        return distributionViews(List.of(distribute(access, input, d.recipientType(), d.recipientTenantId(), d.recipientUserId()))).getFirst();
    }

    /** 分配记录（只给负责人）：recipientType、source 为空时不限；tenantId、bdUserId 只看分给某个商户或 BD 的。 */
    public PartnerDtos.Page<PartnerDtos.Distribution> distributions(Long userId, int page, int size, String recipientType, String source,
                                                                    Long tenantId, Long bdUserId) {
        var access = requireOwner(userId);
        checkPage(page, size);
        if (!blank(recipientType) && !Set.of("TENANT", "BD").contains(recipientType) || !blank(source) && !Set.of("POOL", "ACCOUNT").contains(source))
            throw bad("筛选条件无效");
        Long partnerId = access.partner().id();
        var rows = store.distributions(partnerId, recipientType, source, tenantId, bdUserId, null, page, size);
        return new PartnerDtos.Page<>(distributionViews(rows), page, size, store.distributionCount(partnerId, recipientType, source, tenantId, bdUserId, null));
    }

    List<PartnerDtos.Distribution> distributionViews(List<PartnerStore.Distribution> rows) {
        Map<Long, String> names = tenantNames();
        Map<Long, String> users = new HashMap<>();
        return rows.stream().map(d -> new PartnerDtos.Distribution(d.id(), d.source(), d.recipientType(), d.recipientTenantId(),
                d.recipientUserId() != null ? users.computeIfAbsent(d.recipientUserId(), this::userName) : names.get(d.recipientTenantId()),
                d.recipientUserId(), d.credits(), d.gift(), d.purchased(), d.note(), users.computeIfAbsent(d.actorUserId(), this::userName),
                d.status(), d.createdAt())).toList();
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

    /**
     * 负责人移除 BD。他名下的商户先整体转给 transferTo 指定的 BD，transferTo 为 0 时回到负责人直接管。
     * 负责人账号只能由平台移除。
     */
    @Transactional
    public List<PartnerDtos.Member> removeBd(Long userId, Long bdUserId, long transferTo) {
        var access = requireOwner(userId);
        Long partnerId = access.partner().id();
        store.lockPartner(partnerId);
        if (store.role(partnerId, bdUserId).filter("BD"::equals).isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND, "该账号不是你的 BD");
        if (transferTo != 0) {
            if (transferTo == bdUserId || store.role(partnerId, transferTo).filter("BD"::equals).isEmpty())
                throw BizException.of(ErrorCode.NOT_FOUND, "接手的账号不是你的 BD");
            store.moveBdTenants(partnerId, bdUserId, transferTo);
        }
        store.removeMember(partnerId, bdUserId);
        return members(partnerId);
    }

    /** 按 UID 预查询：添加商户或 BD 前，先确认对方是谁、现在归属在哪。 */
    public PartnerDtos.Lookup lookup(Long userId, Long uid) {
        var access = requirePartner(userId);
        Long partnerId = access.partner().id();
        var user = directory.userByUid(uid).filter(u -> !"REMOVED".equals(u.status()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "没有找到这个 UID，请核对后重新输入"));
        var binding = store.binding(user.tenantId());
        boolean ours = binding.filter(b -> b.partnerId().equals(partnerId)).isPresent();
        String state = binding.isEmpty() ? "NONE" : !ours ? "OTHER"
                : access.owner() || userId.equals(binding.get().bdUserId()) ? "MINE" : "TEAM";
        String bdName = ours && binding.get().bdUserId() != null ? userName(binding.get().bdUserId()) : null;
        String member = access.owner() ? store.role(partnerId, user.id())
                .orElseGet(() -> store.accessOfUser(user.id()).isPresent() ? "OTHER" : "NONE") : null;
        return new PartnerDtos.Lookup(user.uid(), mask(user.name()), tenantNames().get(user.tenantId()), state,
                "MINE".equals(state) ? user.tenantId() : null, bdName, member);
    }

    /** 商户详情：会员、余额、归属，以及这家服务商给他的分配（负责人可见）和他兑换过的本服务商激活码。 */
    public PartnerDtos.TenantDetail tenantDetail(Long userId, Long tenantId) {
        var access = requirePartner(userId);
        Long partnerId = access.partner().id();
        owned(access, tenantId);
        var view = tenantView(tenantId);
        var transfers = access.owner() ? distributionViews(store.distributions(partnerId, "TENANT", null, tenantId, null, null, 0, 50)) : null;
        var facts = usage.usage(tenantId, Instant.now().minus(Duration.ofDays(30)));
        var onboarding = inScope(tenantId, true, () -> new PartnerDtos.Onboarding(view.memberUntil() != null,
                stores.activeCount(), products.activeCount(), facts.firstUsedAt() != null));
        return new PartnerDtos.TenantDetail(view, transfers, codes.redeemedForPartner(partnerId, tenantId), usageOf(facts, 30), onboarding);
    }

    static PartnerDtos.Usage usageOf(com.wuyao.growth.billing.BillingDtos.TenantUsage u, int days) {
        return new PartnerDtos.Usage(days, u.consumed(), u.uses(), u.consumedByCapability(), u.usesByCapability(), u.firstUsedAt(),
                u.lastUsedAt(), u.lastTopUpCredits(), u.lastTopUpAt(), lowBalance(u.available(), u.lastTopUpCredits()));
    }

    /** 余额不足：可用烟火不到最近一笔充值的 10%。没充值过的商户不提醒。 */
    static boolean lowBalance(long available, long lastTopUp) {
        return lastTopUp > 0 && available * 10 <= lastTopUp;
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
        var binding = owned(access, tenantId);
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

    /** 当前账号管得到的商户：负责人管本服务商全部，BD 只管归到自己的。 */
    private PartnerStore.Binding owned(PartnerStore.Access access, Long tenantId) {
        return store.binding(tenantId).filter(b -> b.partnerId().equals(access.partner().id()))
                .filter(b -> access.owner() || access.userId().equals(b.bdUserId()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "该商户不在你名下"));
    }

    String userName(Long id) {
        var u = directory.user(id);
        return u == null || u.name() == null ? "" : u.name();
    }

    /** 姓名只留第一个字，其余打星号；空名返回空。 */
    static String mask(String name) {
        if (name == null || name.isBlank()) return null;
        String trimmed = name.trim();
        int first = trimmed.offsetByCodePoints(0, 1);
        return trimmed.substring(0, first) + "*".repeat(Math.max(1, trimmed.codePointCount(first, trimmed.length())));
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
    List<PartnerStore.Binding> visible(PartnerStore.Access access) {
        var all = store.tenants(access.partner().id());
        return access.owner() ? all : all.stream().filter(b -> access.userId().equals(b.bdUserId())).toList();
    }

    /** 服务商的登录账号，负责人在前；BD 带名下商户数，每个账号带自己生成的激活码统计。 */
    List<PartnerDtos.Member> members(Long partnerId) {
        Map<Long, Long> counts = store.tenants(partnerId).stream().filter(b -> b.bdUserId() != null)
                .collect(Collectors.groupingBy(PartnerStore.Binding::bdUserId, Collectors.counting()));
        Map<Long, PartnerStore.CodeStats> stats = store.codeStats(partnerId);
        return store.members(partnerId).stream().map(m -> {
            var u = directory.user(m.userId());
            var s = stats.getOrDefault(m.userId(), new PartnerStore.CodeStats(0, 0, 0, 0));
            var codeStats = new PartnerDtos.CodeStats(s.generated(), s.redeemed(), s.generated() - s.redeemed() - s.voided(), s.voided(), s.renewals());
            return new PartnerDtos.Member(m.userId(), u == null ? null : u.uid(), u == null ? null : u.name(), u == null ? null : u.phone(),
                    m.role(), "BD".equals(m.role()) ? counts.getOrDefault(m.userId(), 0L) : null, codeStats, m.createdAt());
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

    PartnerDtos.TenantView view(PartnerStore.Binding b, Map<Long, String> names, Map<Long, PlatformAccountDirectory.PlatformUser> owners,
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
    Map<Long, PlatformAccountDirectory.PlatformUser> owners() {
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
