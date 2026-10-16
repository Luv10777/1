package com.wuyao.growth.community;

import com.wuyao.growth.iam.service.AccountClosureGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 注销的人提交过的实名资料（姓名、身份证号密文）直接删除，审核记录里只留下操作痕迹；
 * 商户关闭时，它发布的信息全部下线。
 */
@Component
@RequiredArgsConstructor
public class CommunityAccountClosure implements AccountClosureGuard {
    private final RealNameApplicationRepository identities;
    private final InformationPublicationRepository publications;

    @Override
    public List<String> losses(Closure closure) {
        boolean submitted = closure.userIds().stream()
                .anyMatch(userId -> identities.findByTenantIdAndUserId(closure.tenantId(), userId).isPresent());
        return submitted ? List.of("已提交的实名资料会被删除，以后需要时得重新提交") : List.of();
    }

    @Override
    public void closed(Closure closure) {
        closure.userIds().forEach(userId -> identities.findByTenantIdAndUserId(closure.tenantId(), userId)
                .ifPresent(identities::delete));
        if (closure.wholeTenant()) publications.takeDownAll(closure.tenantId(), Instant.now());
    }
}
