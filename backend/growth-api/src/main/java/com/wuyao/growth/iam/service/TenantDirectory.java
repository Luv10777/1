package com.wuyao.growth.iam.service;

import com.wuyao.growth.iam.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** Public tenant enumeration for system recovery jobs. */
@Service @RequiredArgsConstructor
public class TenantDirectory {
    private final TenantRepository tenants;

    @Transactional(readOnly=true)
    public List<Long> idsAfter(Long after) { return tenants.idsAfter(after,PageRequest.of(0,100)); }

    /** 商户对外显示的名称；商户不存在时为空。 */
    @Transactional(readOnly=true)
    public java.util.Optional<String> name(Long tenantId) {
        return tenants.findById(tenantId).map(com.wuyao.growth.iam.entity.Tenant::getName);
    }
}
