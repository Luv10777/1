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

    public record ApprovedMerchant(String merchantName, String creditCode) {}

    /** 最近一次审核通过的入驻申请。 */
    @Transactional(readOnly = true)
    public Optional<ApprovedMerchant> approvedMerchant() {
        return applications.findAllByTenantIdOrderByCreatedAtDescIdDesc(TenantContext.require()).stream()
                .filter(application -> "APPROVED".equals(application.getStatus()))
                .findFirst()
                .map(application -> new ApprovedMerchant(application.getMerchantName(), application.getCreditCode()));
    }

    /** 当前商户提交过的全部统一社会信用代码（不论审核结果），用来识别同一经营主体。 */
    @Transactional(readOnly = true)
    public Set<String> creditCodes() {
        return applications.findAllByTenantIdOrderByCreatedAtDescIdDesc(TenantContext.require()).stream()
                .map(MerchantApplication::getCreditCode)
                .collect(Collectors.toSet());
    }
}
