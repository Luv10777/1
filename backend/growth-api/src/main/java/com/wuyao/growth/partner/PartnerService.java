package com.wuyao.growth.partner;

import com.wuyao.growth.billing.BillingDtos;
import com.wuyao.growth.billing.BillingRedeemCodeService;
import com.wuyao.growth.billing.BillingService;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.common.web.PageResult;
import com.wuyao.growth.iam.service.AccountService;
import com.wuyao.growth.iam.service.PlatformAccountDirectory;
import com.wuyao.growth.store.StoreMembershipService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 服务商后台（wy.yifangzhi.com/partner/）。每家服务商有一个团队账户（负责人的一方志商户），平台发的烟火进它的账本，
 * 成员都是它的员工：在一方志里创作直接扣它，分给名下商户也从它扣。
 * 登录账号分负责人（OWNER，平台开通，全部权限）和成员（MEMBER，按勾选的权限）；名下商户整个服务商共有，不分到个人。
 * 每个接口先确认当前账号属于一家启用中的服务商、有这项权限，只能动这一家的数据。
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
    private final AccountService accounts;
    private final StoreMembershipService memberships;
    private final PlatformTransactionManager transactionManager;

    public PartnerDtos.Overview overview(Long userId) {
        var access = requirePartner(userId);
        var p = access.partner();
        var team = teamAccount(p);
        Instant monthStart = monthStart();
        long monthCodes = store.codeEvents(p.id(), monthStart).stream().filter(c -> !c.createdAt().isBefore(monthStart)).count();
        return new PartnerDtos.Overview(partnerView(p, team), plans(), store.tenantCount(p.id()), access.member().role(),
                PartnerDtos.Permissions.of(access.perms()), access.member().displayName(), userName(userId), team, monthCodes,
                access.member().memberNumber());
    }

    /** 一方志站内用：当前账号是不是服务商成员（决定右上角要不要显示「服务商后台」）。不是成员时不报错。 */
    public PartnerDtos.Membership membership(Long userId) {
        return store.accessOfUser(userId).map(a -> new PartnerDtos.Membership(true, a.partner().name(), a.member().role(),
                "ACTIVE".equals(a.partner().status()))).orElse(new PartnerDtos.Membership(false, null, null, false));
    }

    /** 团队账户的烟火，在团队账户里读；还没有团队账户或读不到时为空。 */
    PartnerDtos.Account teamAccount(PartnerStore.Row partner) {
        if (partner.teamTenantId() == null) return null;
        try {
            var s = inScope(partner.teamTenantId(), true, () -> billing.summary(false, false));
            return new PartnerDtos.Account(s.available(), s.giftAvailable(), s.purchasedAvailable());
        } catch (RuntimeException e) {
            return null;
        }
    }

    PartnerDtos.Partner partnerView(PartnerStore.Row p, PartnerDtos.Account team) {
        return new PartnerDtos.Partner(p.id(), p.name(), p.contact(), p.status(), team == null ? null : team.available(), p.createdAt());
    }

    /** 能生成激活码的套餐：全部激活码套餐，不限个数。 */
    List<PartnerDtos.Plan> plans() {
        Map<String, BillingDtos.PlanSnapshot> latest = new LinkedHashMap<>();
        for (var plan : billing.activationPlans()) latest.putIfAbsent(plan.code(), plan);
        return latest.values().stream().map(p -> new PartnerDtos.Plan(p.code(), p.name(), p.durationDays())).toList();
    }

    public PartnerDtos.Page<PartnerDtos.TenantView> tenants(Long userId, int page, int size, String keyword, String expiry) {
        var access = require(userId, PartnerStore.Perms::tenants, "商户");
        return tenantViews(store.tenants(access.partner().id()), page, size, keyword, expiry);
    }

    /** 按 UID 把该账号所属的商户加到名下。第一次归属为准：已归属别家服务商的不能加。 */
    public PartnerDtos.TenantView addTenant(Long userId, Long uid) {
        var access = require(userId, PartnerStore.Perms::tenants, "商户");
        var user = directory.userByUid(uid).filter(u -> !"REMOVED".equals(u.status()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "没有找到这个 UID，请核对后重新输入"));
        if (user.tenantId().equals(access.partner().teamTenantId())) throw bad("这是你们的团队账户，不能加成名下商户");
        var binding = store.bindIfAbsent(user.tenantId(), access.partner().id(), "UID", userId);
        if (!binding.partnerId().equals(access.partner().id())) throw BizException.of(ErrorCode.CONFLICT, "该用户已归属其他服务商，不能添加");
        return tenantView(user.tenantId());
    }

    /**
     * 从团队账户分烟火给名下商户（先扣赠送，对方收到时赠送仍是赠送、充值仍是充值）。
     * 成员受每月上限约束；负责人不限。中途失败时用同一 requestKey 再提交即可补上，不会重复扣。
     */
    public PartnerDtos.TenantView transfer(Long userId, Long tenantId, PartnerDtos.Transfer input) {
        var access = require(userId, PartnerStore.Perms::distribute, "分配烟火");
        var binding = owned(access, tenantId);
        distribute(access, input.requestKey(), input.credits(), blank(input.note()) ? null : input.note().trim(), binding.tenantId());
        return tenantView(binding.tenantId());
    }

    /**
     * 分一笔烟火：在团队账户的一个事务里锁服务商、核对上限、扣团队账户、记下分配；再切到商户入账，最后标记到账。
     * 每一步都按请求去重：中途失败时用同一请求再走一遍，已经做过的步骤不会重复。
     * V84 以前从可分配烟火（POOL）分出、还没到账的老记录，补上时只做入账那一步。
     */
    private PartnerStore.Distribution distribute(PartnerStore.Access access, String requestKey, long credits, String note, Long recipientTenant) {
        var partner = access.partner();
        Long actor = access.userId();
        String key = "partner-transfer:" + partner.id() + ":" + requestKey;
        var existing = store.distributionByKey(key);
        if (existing.isPresent()) {
            var d = existing.get();
            checkDistribution(d, recipientTenant, credits);
            if ("DONE".equals(d.status())) return d;
            if ("POOL".equals(d.source())) {
                inScope(recipientTenant, false, () -> billing.creditFromPartner(credits, key, partner.name(), d.actorUserId()));
                tx(false, () -> { store.completeDistribution(key); return null; });
                return store.distributionByKey(key).orElseThrow();
            }
        }
        Long team = partner.teamTenantId();
        if (team == null) throw bad("服务商还没有团队账户，请联系平台");
        if (team.equals(recipientTenant)) throw bad("这是你们的团队账户，不用分");
        String target = "商户「" + tenantNames().get(recipientTenant) + "」";
        var pending = inScope(team, false, () -> {
            var locked = store.lockPartner(partner.id());
            if (!team.equals(locked.teamTenantId()) || !"ACTIVE".equals(locked.status()))
                throw PartnerService.bad("服务商团队账户当前不可分配烟火");
            var recorded = store.distributionByKey(key);
            if (recorded.isPresent()) {
                checkDistribution(recorded.get(), recipientTenant, credits);
                return recorded.get();
            }
            var member = store.member(partner.id(), actor).filter(m -> m.effective().distribute()).orElseThrow(
                    () -> BizException.of(ErrorCode.FORBIDDEN, "你没有「分配烟火」权限，请联系负责人开通"));
            Long cap = member.owner() ? null : member.monthlyCap();
            if (cap != null) {
                long used = store.distributedBy(partner.id(), actor, monthStart());
                if (used + credits > cap) throw bad("超出你每月的分配上限 " + cap + "，本月还能分 " + Math.max(0, cap - used));
            }
            var s = billing.debitForPartner(credits, key, "分配给" + target, actor);
            store.recordDistribution(partner.id(), key, "ACCOUNT", "TENANT", recipientTenant, null, s.gift(), s.purchased(), note, actor);
            return store.distributionByKey(key).orElseThrow();
        });
        if ("DONE".equals(pending.status())) return pending;
        var split = new BillingDtos.CreditSplit(pending.gift(), pending.purchased());
        inScope(recipientTenant, false, () -> billing.creditFromPartnerAccount(split, key, "服务商「" + partner.name() + "」分配烟火", pending.actorUserId()));
        tx(false, () -> { store.completeDistribution(key); return null; });
        return store.distributionByKey(key).orElseThrow();
    }

    private static void checkDistribution(PartnerStore.Distribution existing, Long recipientTenant, long credits) {
        if (!Objects.equals(existing.recipientTenantId(), recipientTenant) || existing.credits() != credits)
            throw bad("分配请求已用于其他对象或数量，请刷新后重试");
    }

    /** 补上一笔「未到账」的分配：用原来的请求再走一遍，已经做过的步骤不会重复，不会再扣一次。 */
    public PartnerDtos.Distribution retryDistribution(Long userId, Long id) {
        var access = require(userId, PartnerStore.Perms::distribute, "分配烟火");
        var d = store.distribution(access.partner().id(), id).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "分配记录不存在"));
        if (!"DONE".equals(d.status())) {
            if (!"TENANT".equals(d.recipientType())) throw bad("这笔是分给 BD 的老记录，不能再补");
            String prefix = "partner-transfer:" + access.partner().id() + ":";
            d = distribute(access, d.requestKey().substring(prefix.length()), d.credits(), d.note(), d.recipientTenantId());
        }
        return distributionViews(access.partner().id(), List.of(d)).getFirst();
    }

    /**
     * 分配记录：有财务权限的看全部（可按商户、操作人筛）；只有分配权限的只看自己分出去的。
     */
    public PartnerDtos.Page<PartnerDtos.Distribution> distributions(Long userId, int page, int size, Long tenantId, Long actorUserId) {
        var access = require(userId, p -> p.finance() || p.distribute(), "分配烟火");
        checkPage(page, size);
        Long partnerId = access.partner().id();
        Long actor = access.perms().finance() ? actorUserId : userId;
        var rows = store.distributions(partnerId, null, null, tenantId, actor, null, page, size);
        return new PartnerDtos.Page<>(distributionViews(partnerId, rows), page, size, store.distributionCount(partnerId, null, null, tenantId, actor, null));
    }

    List<PartnerDtos.Distribution> distributionViews(Long partnerId, List<PartnerStore.Distribution> rows) {
        Map<Long, String> names = tenantNames();
        Map<Long, String> people = memberNames(partnerId);
        var members = store.numberedMembers(partnerId);
        return rows.stream().map(d -> new PartnerDtos.Distribution(d.id(), d.source(), d.recipientType(), d.recipientTenantId(),
                d.recipientUserId() != null ? people.computeIfAbsent(d.recipientUserId(), this::userName) : names.get(d.recipientTenantId()),
                d.recipientUserId(), d.credits(), d.gift(), d.purchased(), d.note(), d.actorUserId(),
                members.containsKey(d.actorUserId()) ? members.get(d.actorUserId()).name() : people.computeIfAbsent(d.actorUserId(), this::userName),
                d.status(), d.createdAt(), members.containsKey(d.actorUserId()) ? members.get(d.actorUserId()).number() : null)).toList();
    }

    /** 团队成员。手机号只给有团队管理权限的人看。 */
    public List<PartnerDtos.Member> team(Long userId) {
        var access = requirePartner(userId);
        return members(access.partner().id(), access.perms().team());
    }

    /**
     * 按手机号加成员：没注册过的在团队账户里新建员工账号（以后用这个手机号登录一方志）；已是团队账户员工的直接加进来；
     * 注册了别的商户的手机号不能加。分配烟火、财务查看和分配上限只有负责人能给；成员只能给出自己也有的权限。
     */
    public List<PartnerDtos.Member> addMember(Long userId, PartnerDtos.NewMember input) {
        var access = require(userId, PartnerStore.Perms::team, "团队管理");
        var partner = access.partner();
        var perms = input.permissions().perms();
        Long cap = perms.distribute() ? input.monthlyCap() : null;
        checkGrant(access, null, perms, null, cap);
        joinTeam(partner, staffByPhone(partner, input.phone(), input.name()), perms, cap, userId);
        return members(partner.id(), true);
    }

    /** 按手机号找团队账户的员工：没注册过的新建员工账号；已是团队账户员工的直接用；注册了别的商户的不行。 */
    Long staffByPhone(PartnerStore.Row partner, String rawPhone, String name) {
        Long team = partner.teamTenantId();
        if (team == null) throw bad("服务商还没有团队账户，请联系平台");
        String phone = rawPhone.trim();
        var existing = directory.users().stream().filter(u -> phone.equals(u.phone()) && !"REMOVED".equals(u.status())).findFirst();
        if (existing.isEmpty()) return inScope(team, false, () -> accounts.createStaff(phone, name).id());
        if (!team.equals(existing.get().tenantId())) throw BizException.of(ErrorCode.CONFLICT, "这个手机号已经注册了其他商户，请换一个手机号");
        if (store.member(partner.id(), existing.get().id()).isPresent()) throw BizException.of(ErrorCode.CONFLICT, "他已经是团队成员了");
        return existing.get().id();
    }

    /** 把团队账户的员工加成成员并设好权限。 */
    void joinTeam(PartnerStore.Row partner, Long memberId, PartnerStore.Perms perms, Long cap, Long actor) {
        tx(false, () -> {
            store.lockPartner(partner.id());
            store.addMember(partner.id(), memberId, "MEMBER", actor).ifPresent(other -> {
                throw BizException.of(ErrorCode.CONFLICT, "该账号已是另一家服务商的成员");
            });
            if (store.member(partner.id(), memberId).orElseThrow().owner()) throw BizException.of(ErrorCode.CONFLICT, "该账号已是本服务商的负责人");
            store.setPermissions(partner.id(), memberId, perms, cap);
            return null;
        });
    }

    /** 改成员的权限、每月分配上限和团队称呼。负责人的权限不能改；不能改自己的权限。 */
    @Transactional
    public List<PartnerDtos.Member> updateMember(Long userId, Long memberId, PartnerDtos.UpdateMember input) {
        var access = require(userId, PartnerStore.Perms::team, "团队管理");
        Long partnerId = access.partner().id();
        store.lockPartner(partnerId);
        var target = store.member(partnerId, memberId).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "该账号不是本服务商的成员"));
        if (target.owner()) throw BizException.of(ErrorCode.FORBIDDEN, "负责人的权限不能改");
        if (memberId.equals(userId)) throw BizException.of(ErrorCode.FORBIDDEN, "不能改自己的权限");
        var perms = input.permissions().perms();
        Long cap = perms.distribute() ? input.monthlyCap() : null;
        checkGrant(access, target.perms(), perms, target.monthlyCap(), cap);
        store.setPermissions(partnerId, memberId, perms, cap);
        if (!blank(input.displayName())) saveDisplayName(partnerId, memberId, input.displayName());
        return members(partnerId, true);
    }

    /**
     * 移除成员：移出服务商；他是团队账户的员工时一并移除（不能再登录一方志、不能再用团队烟火，手机号释放）。
     * 负责人只能由平台移除；不能移除自己。
     */
    public List<PartnerDtos.Member> removeMember(Long userId, Long memberId) {
        var access = require(userId, PartnerStore.Perms::team, "团队管理");
        var partner = access.partner();
        if (memberId.equals(userId)) throw BizException.of(ErrorCode.FORBIDDEN, "不能移除自己");
        var target = store.member(partner.id(), memberId).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "该账号不是本服务商的成员"));
        if (target.owner()) throw BizException.of(ErrorCode.FORBIDDEN, "负责人只能由平台移除");
        leaveTeam(partner, target);
        return members(partner.id(), true);
    }

    /** 移出服务商。成员是团队账户的员工时一并移除；负责人只移出服务商，他自己的一方志账号不动。 */
    void leaveTeam(PartnerStore.Row partner, PartnerStore.MemberRow target) {
        Long memberId = target.userId();
        var user = directory.user(memberId);
        if (!target.owner() && partner.teamTenantId() != null && partner.teamTenantId().equals(user.tenantId()) && AccountService.STAFF.equals(user.role())
                && !"REMOVED".equals(user.status())) {
            inScope(partner.teamTenantId(), false, () -> { accounts.removeStaff(memberId); memberships.removeAll(memberId); return null; });
        }
        tx(false, () -> { store.lockPartner(partner.id()); store.removeMember(partner.id(), memberId); return null; });
    }

    /** 自己在团队里的称呼，第一次进服务商后台时填，之后可以改。 */
    @Transactional
    public PartnerDtos.Overview setDisplayName(Long userId, String name) {
        var access = requirePartner(userId);
        store.lockPartner(access.partner().id());
        saveDisplayName(access.partner().id(), userId, name);
        return overview(userId);
    }

    private void saveDisplayName(Long partnerId, Long memberId, String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 20) throw bad("称呼要 1 到 20 个字");
        if (store.displayNameTaken(partnerId, memberId, name)) throw BizException.of(ErrorCode.CONFLICT, "团队里已经有人叫这个名字了");
        store.setDisplayName(partnerId, memberId, name);
    }

    /**
     * 核对这次能不能把权限从 before 改成 after。分配烟火、财务查看和分配上限只有负责人能动；
     * 成员只能给出自己也有的权限（商户、激活码、团队管理）。before 为空表示新加的成员（全部从无到有）。
     */
    private static void checkGrant(PartnerStore.Access access, PartnerStore.Perms before, PartnerStore.Perms after, Long capBefore, Long capAfter) {
        if (access.owner()) return;
        var from = before == null ? new PartnerStore.Perms(false, false, false, false, false) : before;
        if (from.distribute() != after.distribute() || from.finance() != after.finance() || !Objects.equals(capBefore, capAfter))
            throw BizException.of(ErrorCode.FORBIDDEN, "分配烟火、财务查看和分配上限只有负责人能设");
        var mine = access.perms();
        if (after.tenants() && !from.tenants() && !mine.tenants() || after.codes() && !from.codes() && !mine.codes()
                || after.team() && !from.team() && !mine.team())
            throw BizException.of(ErrorCode.FORBIDDEN, "只能给出你自己也有的权限");
    }

    /** 按 UID 预查询：添加商户前先确认对方是谁、现在归属在哪。 */
    public PartnerDtos.Lookup lookup(Long userId, Long uid) {
        var access = require(userId, PartnerStore.Perms::tenants, "商户");
        Long partnerId = access.partner().id();
        var user = directory.userByUid(uid).filter(u -> !"REMOVED".equals(u.status()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "没有找到这个 UID，请核对后重新输入"));
        var binding = store.binding(user.tenantId());
        boolean ours = binding.filter(b -> b.partnerId().equals(partnerId)).isPresent();
        String state = binding.isEmpty() ? "NONE" : ours ? "MINE" : "OTHER";
        return new PartnerDtos.Lookup(user.uid(), mask(user.name()), tenantNames().get(user.tenantId()), state, ours ? user.tenantId() : null);
    }

    /** 商户详情：会员、余额，这家服务商给他的分配（有分配或财务权限才看得到）和他兑换过的本服务商激活码。 */
    public PartnerDtos.TenantDetail tenantDetail(Long userId, Long tenantId) {
        var access = require(userId, PartnerStore.Perms::tenants, "商户");
        Long partnerId = access.partner().id();
        owned(access, tenantId);
        var view = tenantView(tenantId);
        boolean money = access.perms().distribute() || access.perms().finance();
        var transfers = money ? distributionViews(partnerId, store.distributions(partnerId, "TENANT", null, tenantId, null, null, 0, 50)) : null;
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

    /** 激活码列表，可按生成人筛。 */
    public PageResult<BillingDtos.RedeemCodeView> codes(Long userId, int page, int size, String status, String keyword, Long creator) {
        var access = require(userId, PartnerStore.Perms::codes, "激活码");
        return codes.listForPartner(access.partner().id(), creator, page, size, status, keyword);
    }

    /** 生成激活码（不限个数，每批最多 200 个），按该套餐标识最新的版本开通。同一 requestKey 重复提交返回第一次那批。 */
    @Transactional
    public List<BillingDtos.RedeemCodeView> createCodes(Long userId, PartnerDtos.NewCodes input) {
        var access = require(userId, PartnerStore.Perms::codes, "激活码");
        return generate(access, input.planCode(), input.quantity(), "partner-" + access.partner().id() + "-" + input.requestKey(),
                blank(input.note()) ? null : input.note().trim(), userId);
    }

    /** 作废还没兑换的激活码。 */
    public BillingDtos.RedeemCodeView voidCode(Long userId, Long codeId) {
        var access = require(userId, PartnerStore.Perms::codes, "激活码");
        return codes.voidForPartner(codeId, access.partner().id(), null, userId);
    }

    /**
     * 直接续期：给名下商户开通或顺延会员，不用把码发给商户再兑换。在商户的租户里一个事务做完生成码、兑换；
     * 同一 requestKey 重复提交不重复顺延。生成的码备注「直接续期」，在激活码列表里能对账。
     */
    public PartnerDtos.TenantView renew(Long userId, Long tenantId, PartnerDtos.Renew input) {
        var access = require(userId, PartnerStore.Perms::tenants, "商户");
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
        return ledgerOf(require(userId, PartnerStore.Perms::finance, "财务查看").partner().id(), page, size);
    }

    /** 本服务商名下的商户（整个服务商共有）。 */
    PartnerStore.Binding owned(PartnerStore.Access access, Long tenantId) {
        return store.binding(tenantId).filter(b -> b.partnerId().equals(access.partner().id()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "该商户不在你名下"));
    }

    String userName(Long id) {
        var u = directory.user(id);
        return u == null || u.name() == null ? "" : u.name();
    }

    /** 成员在团队里的称呼，没填的用账号名。 */
    Map<Long, String> memberNames(Long partnerId) {
        Map<Long, String> names = new HashMap<>();
        for (var m : store.members(partnerId)) names.put(m.userId(), blank(m.displayName()) ? userName(m.userId()) : m.displayName());
        return names;
    }

    /** 姓名只留第一个字，其余打星号；空名返回空。 */
    static String mask(String name) {
        if (name == null || name.isBlank()) return null;
        String trimmed = name.trim();
        int first = trimmed.offsetByCodePoints(0, 1);
        return trimmed.substring(0, first) + "*".repeat(Math.max(1, trimmed.codePointCount(first, trimmed.length())));
    }

    private List<BillingDtos.RedeemCodeView> generate(PartnerStore.Access access, String planCode, int quantity, String batch, String note, Long userId) {
        var plan = billing.activationPlans().stream().filter(p -> p.code().equals(planCode)).findFirst()
                .orElseThrow(() -> bad("没有这个套餐，不能生成激活码"));
        return codes.createForPartner(access.partner().id(), plan.id(), quantity, batch, note, userId);
    }

    public Long auditPartnerId(Long userId) { return requirePartner(userId).partner().id(); }

    PartnerStore.Access requirePartner(Long userId) {
        var access = store.accessOfUser(userId)
                .orElseThrow(() -> BizException.of(ErrorCode.FORBIDDEN, "这个账号还不是服务商成员，请联系服务商负责人或平台"));
        if (!"ACTIVE".equals(access.partner().status())) throw BizException.of(ErrorCode.FORBIDDEN, "服务商已停用，请联系平台");
        return access;
    }

    /** 当前账号要有某项权限（负责人全部都有）。 */
    PartnerStore.Access require(Long userId, Predicate<PartnerStore.Perms> permission, String name) {
        var access = requirePartner(userId);
        if (!permission.test(access.perms())) throw BizException.of(ErrorCode.FORBIDDEN, "你没有「" + name + "」权限，请联系负责人开通");
        return access;
    }

    /** 服务商的登录账号，负责人在前；带权限、本月分配、按 UID 加的商户数和生成的激活码统计。 */
    List<PartnerDtos.Member> members(Long partnerId, boolean withPhone) {
        Map<Long, PartnerStore.CodeStats> stats = store.codeStats(partnerId);
        Map<Long, Long> added = store.tenants(partnerId).stream().filter(b -> "UID".equals(b.source()) && b.changedBy() != null)
                .collect(Collectors.groupingBy(PartnerStore.Binding::changedBy, Collectors.counting()));
        Instant monthStart = monthStart();
        return store.members(partnerId).stream().map(m -> {
            var u = directory.user(m.userId());
            var s = stats.getOrDefault(m.userId(), new PartnerStore.CodeStats(0, 0, 0, 0));
            var codeStats = new PartnerDtos.CodeStats(s.generated(), s.redeemed(), s.generated() - s.redeemed() - s.voided(), s.voided(), s.renewals());
            return new PartnerDtos.Member(m.userId(), u == null ? null : u.uid(), u == null ? null : u.name(), m.displayName(),
                    withPhone && u != null ? u.phone() : null, m.role(), PartnerDtos.Permissions.of(m.effective()), m.owner() ? null : m.monthlyCap(),
                    store.distributedBy(partnerId, m.userId(), monthStart), added.getOrDefault(m.userId(), 0L), codeStats, m.createdAt(), m.memberNumber());
        }).toList();
    }

    PartnerDtos.Page<PartnerDtos.LedgerEntry> ledgerOf(Long partnerId, int page, int size) {
        checkPage(page, size);
        Map<Long, String> names = tenantNames();
        Map<Long, String> people = new HashMap<>();
        var items = store.ledger(partnerId, page, size).stream().map(l -> new PartnerDtos.LedgerEntry(l.id(), l.type(), l.delta(),
                l.availableAfter(), l.tenantId(), l.tenantId() == null ? null : names.get(l.tenantId()), l.note(),
                people.computeIfAbsent(l.actorUserId(), this::userName), l.createdAt())).toList();
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
        Map<Long, String> people = new HashMap<>();
        String key = blank(keyword) ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        var matched = bindings.stream().filter(b -> key.isEmpty() || Stream.of(names.get(b.tenantId()), String.valueOf(b.tenantId()),
                        owners.get(b.tenantId()) == null ? null : owners.get(b.tenantId()).name(),
                        owners.get(b.tenantId()) == null ? null : String.valueOf(owners.get(b.tenantId()).uid()))
                .filter(Objects::nonNull).anyMatch(v -> v.toLowerCase(Locale.ROOT).contains(key))).toList();
        if (blank(expiry)) {
            int start = (int) Math.min((long) page * size, matched.size()), end = Math.min(start + size, matched.size());
            var items = matched.subList(start, end).stream().map(b -> view(b, names, owners, people)).toList();
            return new PartnerDtos.Page<>(items, page, size, matched.size());
        }
        Instant now = Instant.now();
        var filtered = matched.stream().map(b -> view(b, names, owners, people)).filter(v -> expiring(v, expiry, now))
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

    /** people 缓存添加人的称呼：同一家服务商的成员用团队称呼，平台运营等其他人用账号名。 */
    PartnerDtos.TenantView view(PartnerStore.Binding b, Map<Long, String> names, Map<Long, PlatformAccountDirectory.PlatformUser> owners,
                                Map<Long, String> people) {
        var owner = owners.get(b.tenantId());
        BillingDtos.Summary summary;
        try { summary = inScope(b.tenantId(), true, () -> billing.summary(false, false)); }
        catch (RuntimeException e) { summary = null; }
        String addedBy = "UID".equals(b.source()) && b.changedBy() != null ? people.computeIfAbsent(b.changedBy(), id ->
                store.member(b.partnerId(), id).map(PartnerStore.MemberRow::displayName).filter(n -> !blank(n)).orElseGet(() -> userName(id))) : null;
        return new PartnerDtos.TenantView(b.tenantId(), names.get(b.tenantId()), owner == null ? null : owner.uid(), owner == null ? null : owner.name(),
                b.source(), b.boundAt(), summary == null ? null : summary.memberActive(), summary == null ? null : summary.memberUntil(),
                summary == null || summary.plan() == null ? null : summary.plan().name(), summary == null ? null : summary.available(), addedBy);
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

    /** 本月第一天零点（北京时间）。 */
    static Instant monthStart() {
        return LocalDate.now(PartnerInsights.ZONE).withDayOfMonth(1).atStartOfDay(PartnerInsights.ZONE).toInstant();
    }

    private <T> T tx(boolean readOnly, Supplier<T> work) {
        var tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setReadOnly(readOnly);
        return tx.execute(status -> work.get());
    }

    /** 商户的账本受 RLS 约束：切到该商户后新开事务。 */
    <T> T inScope(Long tenantId, boolean readOnly, Supplier<T> work) {
        return TenantContext.runAs(tenantId, () -> tx(readOnly, work));
    }

    static void checkPage(int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100) throw bad("分页参数无效");
    }

    static boolean blank(String value) { return value == null || value.isBlank(); }

    static BizException bad(String message) { return BizException.of(ErrorCode.BAD_REQUEST, message); }
}
