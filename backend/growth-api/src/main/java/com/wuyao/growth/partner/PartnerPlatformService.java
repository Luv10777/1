package com.wuyao.growth.partner;

import com.wuyao.growth.billing.BillingProperties;
import com.wuyao.growth.billing.BillingService;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.iam.service.PlatformAccountDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 平台管理服务商：建服务商、绑登录账号、线下收款后发烟火、按套餐标识分激活码额度、查看和改名下商户归属。
 * 涉及烟火和激活码，只开放给计费运营名单（BILLING_OPERATOR_USER_IDS）。
 */
@Service
@RequiredArgsConstructor
public class PartnerPlatformService {
    private final PartnerStore store;
    private final PartnerService partners;
    private final PlatformAccountDirectory directory;
    private final BillingService billing;
    private final BillingProperties config;

    public List<PartnerDtos.PlatformPartner> list(Long operator) {
        requireOperator(operator);
        return store.partners().stream().map(this::view).toList();
    }

    @Transactional
    public PartnerDtos.PlatformPartner create(PartnerDtos.NewPartner input, Long operator) {
        requireOperator(operator);
        var admin = user(input.adminUid());
        Long id = store.createPartner(input.name().trim(), PartnerService.blank(input.contact()) ? null : input.contact().trim(), operator);
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

    @Transactional
    public PartnerDtos.PlatformPartner removeMember(Long partnerId, Long userId, Long operator) {
        requireOperator(operator);
        var row = store.lockPartner(partnerId);
        if (store.removeMember(partnerId, userId) == 0) throw BizException.of(ErrorCode.NOT_FOUND, "该账号不是这家服务商的登录账号");
        return view(row);
    }

    @Transactional
    public PartnerDtos.PlatformPartner setStatus(Long partnerId, String status, Long operator) {
        requireOperator(operator);
        store.lockPartner(partnerId);
        store.setStatus(partnerId, status);
        return view(store.partner(partnerId).orElseThrow());
    }

    /** 线下收款后给服务商发烟火；同一 requestKey 只发一次。 */
    @Transactional
    public PartnerDtos.PlatformPartner grant(Long partnerId, PartnerDtos.Grant input, Long operator) {
        requireOperator(operator);
        var row = store.lockPartner(partnerId);
        String key = "partner-grant:" + input.requestKey();
        if (!store.eventExists(key)) store.move(row, key, "GRANT", input.credits(), null, input.note().trim(), operator);
        return view(store.partner(partnerId).orElseThrow());
    }

    /** 设某个套餐标识的激活码总额度（例如 20 个），不能低于已经生成的个数。 */
    @Transactional
    public PartnerDtos.PlatformPartner setQuota(Long partnerId, PartnerDtos.SetQuota input, Long operator) {
        requireOperator(operator);
        var row = store.lockPartner(partnerId);
        if (billing.plans().stream().noneMatch(p -> p.code().equals(input.planCode()))) throw PartnerService.bad("该套餐未上架，不能分配额度");
        store.setQuota(partnerId, input.planCode(), input.quota());
        return view(row);
    }

    public PartnerDtos.Page<PartnerDtos.TenantView> tenants(Long partnerId, int page, int size, String keyword, Long operator) {
        requireOperator(operator);
        store.partner(partnerId).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "服务商不存在"));
        return partners.tenantViews(store.tenants(partnerId), page, size, keyword);
    }

    public PartnerDtos.Page<PartnerDtos.LedgerEntry> ledger(Long partnerId, int page, int size, Long operator) {
        requireOperator(operator);
        return partners.ledgerOf(partnerId, page, size);
    }

    /** 按用户 ID 查这个账号的商户现在归属哪家服务商，改归属前先查。 */
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

    private void addMember(Long partnerId, PlatformAccountDirectory.PlatformUser user, Long operator) {
        store.addMember(partnerId, user.id(), operator).ifPresent(other -> {
            throw BizException.of(ErrorCode.CONFLICT, "该账号已是另一家服务商的登录账号");
        });
    }

    private PlatformAccountDirectory.PlatformUser user(Long uid) {
        return directory.userByUid(uid).filter(u -> !"REMOVED".equals(u.status()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "没有找到这个用户 ID，请核对后重新输入"));
    }

    private PartnerDtos.PlatformPartner view(PartnerStore.Row row) {
        Map<Long, PlatformAccountDirectory.PlatformUser> users = store.members(row.id()).stream()
                .map(m -> directory.user(m.userId())).filter(Objects::nonNull)
                .collect(Collectors.toMap(PlatformAccountDirectory.PlatformUser::id, u -> u));
        var members = store.members(row.id()).stream().map(m -> {
            var u = users.get(m.userId());
            return new PartnerDtos.Member(m.userId(), u.uid(), u.name(), u.phone(), m.createdAt());
        }).toList();
        return new PartnerDtos.PlatformPartner(row.view(), members, partners.quotas(row.id()), store.tenantCount(row.id()));
    }

    private void requireOperator(Long operator) {
        if (!config.getOperatorUserIds().contains(operator)) throw BizException.of(ErrorCode.FORBIDDEN, "没有服务商管理权限");
    }
}
