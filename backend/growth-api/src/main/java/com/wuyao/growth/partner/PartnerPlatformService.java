package com.wuyao.growth.partner;

import com.wuyao.growth.billing.BillingProperties;
import com.wuyao.growth.billing.BillingRedeemCodeService;
import com.wuyao.growth.billing.BillingService;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.iam.service.PlatformAccountDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 平台管理服务商：建服务商（负责人的一方志商户就是团队账户）、绑负责人账号、线下收款后把烟火发进团队账户、
 * 查看和改名下商户归属。服务商生成激活码不限个数。
 * 涉及烟火和激活码，只开放给计费运营名单（BILLING_OPERATOR_USER_IDS）。
 */
@Service
@RequiredArgsConstructor
public class PartnerPlatformService {
    private final PartnerStore store;
    private final PartnerService partners;
    private final PlatformAccountDirectory directory;
    private final BillingService billing;
    private final com.wuyao.growth.backoffice.BackofficeAccess access;
    private final BillingRedeemCodeService codes;

    public List<PartnerDtos.PlatformPartner> list(Long operator) {
        requireOperator(operator);
        return store.partners().stream().map(this::view).toList();
    }

    @Transactional
    public PartnerDtos.PlatformPartner create(PartnerDtos.NewPartner input, Long operator) {
        requireOperator(operator);
        var admin = user(input.adminUid());
        Long id = store.createPartner(input.name().trim(), PartnerService.blank(input.contact()) ? null : input.contact().trim(), admin.tenantId(), operator);
        addMember(id, admin, operator);
        return view(store.partner(id).orElseThrow());
    }

    @Transactional
    public PartnerDtos.PlatformPartner addMember(Long partnerId, Long uid, Long operator) {
        requireOperator(operator);
        var row = store.lockPartner(partnerId);
        addMember(row.id(), user(uid), operator);
        return view(row);
    }

    /**
     * 平台加成员，权限随平台勾选（不受「只有负责人能给」的限制）。按 UID 加的必须已是团队账户的员工；
     * 按手机号加的和服务商自己在团队页加一样：没注册过的在团队账户里新建员工账号，注册了别的商户的不行。
     */
    public PartnerDtos.PlatformPartner addTeamMember(Long partnerId, PartnerDtos.PlatformMember input, Long operator) {
        requireOperator(operator);
        var row = store.partner(partnerId).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "服务商不存在"));
        boolean byUid = input.uid() != null;
        if (byUid == !PartnerService.blank(input.phone())) throw PartnerService.bad("UID 和手机号填其中一个");
        if (!byUid && PartnerService.blank(input.name())) throw PartnerService.bad("按手机号添加要填姓名");
        var perms = input.permissions().perms();
        Long cap = perms.distribute() ? input.monthlyCap() : null;
        Long memberId;
        if (byUid) {
            var u = user(input.uid());
            if (row.teamTenantId() == null) throw PartnerService.bad("这家服务商还没有团队账户，先添加负责人");
            if (!row.teamTenantId().equals(u.tenantId()))
                throw BizException.of(ErrorCode.CONFLICT, "这个 UID 不是团队账户的员工，加不进团队；可以改用手机号添加");
            if (store.member(row.id(), u.id()).isPresent()) throw BizException.of(ErrorCode.CONFLICT, "他已经是团队成员了");
            memberId = u.id();
        } else {
            memberId = partners.staffByPhone(row, input.phone(), input.name().trim());
        }
        partners.joinTeam(row, memberId, perms, cap, operator);
        return view(store.partner(partnerId).orElseThrow());
    }

    /** 移除负责人只移出服务商；移除成员和服务商自己在团队页移除一样，一并停用他在团队账户的员工账号。 */
    public PartnerDtos.PlatformPartner removeMember(Long partnerId, Long userId, Long operator) {
        requireOperator(operator);
        var row = store.partner(partnerId).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "服务商不存在"));
        var target = store.member(partnerId, userId).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "该账号不是这家服务商的登录账号"));
        partners.leaveTeam(row, target);
        return view(store.partner(partnerId).orElseThrow());
    }

    /**
     * 删除服务商（软删除）：作废没兑换的激活码，移出全部登录账号，名下商户解除归属；记录保留，团队账户余额不动。
     * 还有没到账的分配时不能删，否则那几笔再也补不上。
     */
    @Transactional
    public void delete(Long partnerId, Long operator) {
        requireOperator(operator);
        store.lockPartner(partnerId);
        long pending = store.pendingDistributions(partnerId);
        if (pending > 0) throw BizException.of(ErrorCode.CONFLICT, "还有 " + pending + " 笔分给商户的烟火没到账，请先让服务商在「烟火」页补上再删除");
        codes.voidUnusedOfPartner(partnerId, operator);
        store.delete(partnerId);
    }

    /** 改服务商名称和联系方式；名称即服务商后台里显示的服务商名。 */
    @Transactional
    public PartnerDtos.PlatformPartner rename(Long partnerId, PartnerDtos.Rename input, Long operator) {
        requireOperator(operator);
        store.lockPartner(partnerId);
        store.rename(partnerId, input.name().trim(), PartnerService.blank(input.contact()) ? null : input.contact().trim());
        return view(store.partner(partnerId).orElseThrow());
    }

    @Transactional
    public PartnerDtos.PlatformPartner setStatus(Long partnerId, String status, Long operator) {
        requireOperator(operator);
        store.lockPartner(partnerId);
        store.setStatus(partnerId, status);
        return view(store.partner(partnerId).orElseThrow());
    }

    /**
     * 线下收款后给服务商发烟火：记入团队账户的充值烟火，服务商流水记一条发放。两边在团队账户的同一个事务里写；
     * 同一 requestKey 只发一次。
     */
    public PartnerDtos.PlatformPartner grant(Long partnerId, PartnerDtos.Grant input, Long operator) {
        requireOperator(operator);
        var row = store.partner(partnerId).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "服务商不存在"));
        if (row.teamTenantId() == null) throw PartnerService.bad("这家服务商还没有团队账户，先添加负责人");
        String key = "partner-grant:" + input.requestKey();
        String note = input.note().trim();
        partners.inScope(row.teamTenantId(), false, () -> {
            store.lockPartner(partnerId);
            if (store.eventExists(key)) return null;
            billing.creditPartnerGrant(input.credits(), input.requestKey(), row.name(), note, operator);
            store.recordGrant(partnerId, key, input.credits(), note, operator);
            return null;
        });
        return view(store.partner(partnerId).orElseThrow());
    }

    public PartnerDtos.Page<PartnerDtos.TenantView> tenants(Long partnerId, int page, int size, String keyword, Long operator) {
        requireOperator(operator);
        store.partner(partnerId).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "服务商不存在"));
        return partners.tenantViews(store.tenants(partnerId), page, size, keyword, null);
    }

    public PartnerDtos.Page<PartnerDtos.LedgerEntry> ledger(Long partnerId, int page, int size, Long operator) {
        requireOperator(operator);
        return partners.ledgerOf(partnerId, page, size);
    }

    /** 按 UID 查这个账号的商户现在归属哪家服务商，改归属前先查。 */
    public Map<String, Object> lookup(Long uid, Long operator) {
        requireOperator(operator);
        var u = user(uid);
        var binding = store.binding(u.tenantId());
        var partner = binding.flatMap(b -> store.partner(b.partnerId()));
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("uid", u.uid()); result.put("userName", u.name()); result.put("tenantId", u.tenantId());
        result.put("tenantName", partners.tenantNames().get(u.tenantId()));
        result.put("partnerId", partner.map(PartnerStore.Row::id).orElse(null));
        result.put("partnerName", partner.map(PartnerStore.Row::name).orElse(null));
        result.put("source", binding.map(PartnerStore.Binding::source).orElse(null));
        result.put("boundAt", binding.map(PartnerStore.Binding::boundAt).orElse(null));
        return result;
    }

    /** 平台改归属（只有平台能改）；partnerId 为空时解除归属。 */
    @Transactional
    public void reassign(Long tenantId, Long partnerId, Long operator) {
        requireOperator(operator);
        if (partnerId != null) store.lockPartner(partnerId);
        if (partners.tenantNames().get(tenantId) == null) throw BizException.of(ErrorCode.NOT_FOUND, "商户不存在");
        store.reassign(tenantId, partnerId, operator);
    }

    /** 平台加的都是负责人；这个账号已是本服务商成员时升为负责人。 */
    private void addMember(Long partnerId, PlatformAccountDirectory.PlatformUser user, Long operator) {
        store.addMember(partnerId, user.id(), "OWNER", operator).ifPresent(other -> {
            throw BizException.of(ErrorCode.CONFLICT, "该账号已是另一家服务商的登录账号");
        });
        store.setRole(partnerId, user.id(), "OWNER");
    }

    private PlatformAccountDirectory.PlatformUser user(Long uid) {
        return directory.userByUid(uid).filter(u -> !"REMOVED".equals(u.status()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "没有找到这个 UID，请核对后重新输入"));
    }

    private PartnerDtos.PlatformPartner view(PartnerStore.Row row) {
        return new PartnerDtos.PlatformPartner(partners.partnerView(row, partners.teamAccount(row)), partners.members(row.id(), true),
                store.tenantCount(row.id()), row.teamTenantId(), row.teamTenantId() == null ? null : partners.tenantNames().get(row.teamTenantId()));
    }

    private void requireOperator(Long operator) {
        access.require(operator, "PARTNERS");
    }
}
