package com.wuyao.growth.community;

import com.wuyao.growth.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** 供其他模块查询当前商户的入驻审核结果。只读，只看当前租户。 */
@Service
@RequiredArgsConstructor
public class MerchantQualificationService {
    private final MerchantApplicationRepository applications;

    public record ApprovedMerchant(String merchantName, String companyName, String creditCode) {}

    /** 最近一次审核通过的入驻申请。 */
    @Transactional(readOnly = true)
    public Optional<ApprovedMerchant> approvedMerchant() {
        return applications.findAllByTenantIdOrderByCreatedAtDescIdDesc(TenantContext.require()).stream()
                .filter(application -> "APPROVED".equals(application.getStatus()))
                .findFirst()
                .map(application -> new ApprovedMerchant(application.getMerchantName(), application.getCompanyName(), application.getCreditCode()));
    }

    /** 商城好物「入驻商户」栏的一个商户。tenantId 只给服务端取商品用，不对外返回。 */
    public record PublicMerchant(Long id, Long tenantId, String merchantName) {}

    /** 全平台入驻审核通过的商户，和管理后台「已入驻商家」一致；不需要租户上下文。 */
    @Transactional(readOnly = true)
    public com.wuyao.growth.common.web.PageResult<PublicMerchant> publicApprovedMerchants(int page, int size) {
        if (page < 0 || size < 1 || size > 50) {
            throw com.wuyao.growth.common.web.BizException.of(com.wuyao.growth.common.web.ErrorCode.BAD_REQUEST,
                    "page 必须非负，size 必须在 1 到 50 之间");
        }
        java.util.List<Object[]> rows = applications.findPublicAllianceMerchants(size, (long) page * size);
        long total = rows.isEmpty() ? 0 : ((Number) rows.get(0)[3]).longValue();
        java.util.List<PublicMerchant> items = rows.stream().map(row -> new PublicMerchant(
                ((Number) row[0]).longValue(), ((Number) row[1]).longValue(), (String) row[2])).toList();
        return new com.wuyao.growth.common.web.PageResult<>(items, page, size, total, (int) ((total + size - 1) / size));
    }

    /** 线上卖货、发布信息、提供联盟券都要求入驻审核通过。 */
    public void requireApproved(String action) {
        if (approvedMerchant().isEmpty()) {
            throw com.wuyao.growth.common.web.BizException.of(com.wuyao.growth.common.web.ErrorCode.FORBIDDEN,
                    "请先通过商家入驻审核，再" + action);
        }
    }

    /** 当前商户提交过的全部统一社会信用代码（不论审核结果），用来识别同一经营主体。 */
    @Transactional(readOnly = true)
    public Set<String> creditCodes() {
        return applications.findAllByTenantIdOrderByCreatedAtDescIdDesc(TenantContext.require()).stream()
                .map(MerchantApplication::getCreditCode)
                .collect(Collectors.toSet());
    }
}
